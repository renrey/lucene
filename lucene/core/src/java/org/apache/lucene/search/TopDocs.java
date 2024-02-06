/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.search;

import java.util.Comparator;
import org.apache.lucene.util.PriorityQueue;

/** Represents hits returned by {@link IndexSearcher#search(Query,int)}. */
public class TopDocs {

  /** The total number of hits for the query. */
  public TotalHits totalHits;

  /** The top hits for the query. */
  public ScoreDoc[] scoreDocs;

  /** Internal comparator with shardIndex */
  private static final Comparator<ScoreDoc> SHARD_INDEX_TIE_BREAKER =
      Comparator.comparingInt(d -> d.shardIndex);

  /** Internal comparator with docID */
  private static final Comparator<ScoreDoc> DOC_ID_TIE_BREAKER =
      Comparator.comparingInt(d -> d.doc);

  /** Default comparator */
  private static final Comparator<ScoreDoc> DEFAULT_TIE_BREAKER =
      SHARD_INDEX_TIE_BREAKER.thenComparing(DOC_ID_TIE_BREAKER);

  /** Constructs a TopDocs. */
  public TopDocs(TotalHits totalHits, ScoreDoc[] scoreDocs) {
    this.totalHits = totalHits;
    this.scoreDocs = scoreDocs;
  }

  // Refers to one hit:
  private static final class ShardRef {
    // Which shard (index into shardHits[]):
    final int shardIndex;// shard下标（当前对象的标识）

    // Which hit within the shard:
    int hitIndex;// 当前指向的doc下标（循环指针）

    ShardRef(int shardIndex) {
      this.shardIndex = shardIndex;
    }

    @Override
    public String toString() {
      return "ShardRef(shardIndex=" + shardIndex + " hitIndex=" + hitIndex + ")";
    }
  }

  /**
   * Use the tie breaker if provided. If tie breaker returns 0 signifying equal values, we use hit
   * indices to tie break intra shard ties
   */
  static boolean tieBreakLessThan(
      ShardRef first,
      ScoreDoc firstDoc,
      ShardRef second,
      ScoreDoc secondDoc,
      Comparator<ScoreDoc> tieBreaker) {
    assert tieBreaker != null;
    int value = tieBreaker.compare(firstDoc, secondDoc);

    if (value == 0) {
      // Equal Values
      // Tie break in same shard: resolve however the
      // shard had resolved it:
      assert first.hitIndex != second.hitIndex;
      return first.hitIndex < second.hitIndex;
    }

    return value < 0;
  }

  // Specialized MergeSortQueue that just merges by
  // relevance score, descending:
  private static class ScoreMergeSortQueue extends PriorityQueue<ShardRef> {
    final ScoreDoc[][] shardHits;
    final Comparator<ScoreDoc> tieBreakerComparator;

    public ScoreMergeSortQueue(TopDocs[] shardHits, Comparator<ScoreDoc> tieBreakerComparator) {
      super(shardHits.length);
      this.shardHits = new ScoreDoc[shardHits.length][];
      for (int shardIDX = 0; shardIDX < shardHits.length; shardIDX++) {
        this.shardHits[shardIDX] = shardHits[shardIDX].scoreDocs;
      }
      this.tieBreakerComparator = tieBreakerComparator;
    }

    // Returns true if first is < second
    @Override
    public boolean lessThan(ShardRef first, ShardRef second) {
      assert first != second;
      ScoreDoc firstScoreDoc = shardHits[first.shardIndex][first.hitIndex];
      ScoreDoc secondScoreDoc = shardHits[second.shardIndex][second.hitIndex];
      if (firstScoreDoc.score < secondScoreDoc.score) {
        return false;
      } else if (firstScoreDoc.score > secondScoreDoc.score) {
        return true;
      } else {
        return tieBreakLessThan(first, firstScoreDoc, second, secondScoreDoc, tieBreakerComparator);
      }
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static class MergeSortQueue extends PriorityQueue<ShardRef> {
    // These are really FieldDoc instances:
    final ScoreDoc[][] shardHits;
    final FieldComparator<?>[] comparators;
    final int[] reverseMul;
    final Comparator<ScoreDoc> tieBreaker;

    public MergeSortQueue(Sort sort, TopDocs[] shardHits, Comparator<ScoreDoc> tieBreaker) {
      super(shardHits.length);
      this.shardHits = new ScoreDoc[shardHits.length][];
      this.tieBreaker = tieBreaker;
      for (int shardIDX = 0; shardIDX < shardHits.length; shardIDX++) {
        final ScoreDoc[] shard = shardHits[shardIDX].scoreDocs;
        // System.out.println("  init shardIdx=" + shardIDX + " hits=" + shard);
        if (shard != null) {
          this.shardHits[shardIDX] = shard;
          // Fail gracefully if API is misused:
          for (int hitIDX = 0; hitIDX < shard.length; hitIDX++) {
            final ScoreDoc sd = shard[hitIDX];
            if (!(sd instanceof FieldDoc)) {
              throw new IllegalArgumentException(
                  "shard "
                      + shardIDX
                      + " was not sorted by the provided Sort (expected FieldDoc but got ScoreDoc)");
            }
            final FieldDoc fd = (FieldDoc) sd;
            if (fd.fields == null) {
              throw new IllegalArgumentException(
                  "shard " + shardIDX + " did not set sort field values (FieldDoc.fields is null)");
            }
          }
        }
      }

      final SortField[] sortFields = sort.getSort();
      // 多种字段的比较器数组，每个字段1个比较器
      comparators = new FieldComparator[sortFields.length];
      reverseMul = new int[sortFields.length];
      for (int compIDX = 0; compIDX < sortFields.length; compIDX++) {
        final SortField sortField = sortFields[compIDX];
        comparators[compIDX] = sortField.getComparator(1, compIDX == 0);
        reverseMul[compIDX] = sortField.getReverse() ? -1 : 1;
      }
    }

    // 重写了比较大小
    // Returns true if first is < second
    @Override
    public boolean lessThan(ShardRef first, ShardRef second) {
      assert first != second;
      // 就是拿到这2个shard当前指向的doc
      final FieldDoc firstFD = (FieldDoc) shardHits[first.shardIndex][first.hitIndex];
      final FieldDoc secondFD = (FieldDoc) shardHits[second.shardIndex][second.hitIndex];
      // System.out.println("  lessThan:\n     first=" + first + " doc=" + firstFD.doc + " score=" +
      // firstFD.score + "\n    second=" + second + " doc=" + secondFD.doc + " score=" +
      // secondFD.score);

      // 遍历字段comparators来比较
      for (int compIDX = 0; compIDX < comparators.length; compIDX++) {
        final FieldComparator comp = comparators[compIDX];
        // System.out.println("    cmp idx=" + compIDX + " cmp1=" + firstFD.fields[compIDX] + "
        // cmp2=" + secondFD.fields[compIDX] + " reverse=" + reverseMul[compIDX]);

        final int cmp =
            reverseMul[compIDX]
                * comp.compareValues(firstFD.fields[compIDX], secondFD.fields[compIDX]);
        // 只要不相等，就返回大小结果
        if (cmp != 0) {
          // System.out.println("    return " + (cmp < 0));
          return cmp < 0;
        }
      }
      return tieBreakLessThan(first, firstFD, second, secondFD, tieBreaker);
    }
  }

  /**
   * Returns a new TopDocs, containing topN results across the provided TopDocs, sorting by score.
   * Each {@link TopDocs} instance must be sorted.
   *
   * @see #merge(int, int, TopDocs[])
   * @lucene.experimental
   */
  public static TopDocs merge(int topN, TopDocs[] shardHits) {
    return merge(0, topN, shardHits);
  }

  /**
   * Same as {@link #merge(int, TopDocs[])} but also ignores the top {@code start} top docs. This is
   * typically useful for pagination.
   *
   * <p>docIDs are expected to be in consistent pattern i.e. either all ScoreDocs have their
   * shardIndex set, or all have them as -1 (signifying that all hits belong to same searcher)
   *
   * @lucene.experimental
   */
  public static TopDocs merge(int start, int topN, TopDocs[] shardHits) {
    return mergeAux(null, start, topN, shardHits, DEFAULT_TIE_BREAKER);
  }

  /**
   * Same as above, but accepts the passed in tie breaker
   *
   * <p>docIDs are expected to be in consistent pattern i.e. either all ScoreDocs have their
   * shardIndex set, or all have them as -1 (signifying that all hits belong to same searcher)
   *
   * @lucene.experimental
   */
  public static TopDocs merge(
      int start, int topN, TopDocs[] shardHits, Comparator<ScoreDoc> tieBreaker) {
    return mergeAux(null, start, topN, shardHits, tieBreaker);
  }

  /**
   * Returns a new TopFieldDocs, containing topN results across the provided TopFieldDocs, sorting
   * by the specified {@link Sort}. Each of the TopDocs must have been sorted by the same Sort, and
   * sort field values must have been filled (ie, <code>fillFields=true</code> must be passed to
   * {@link TopFieldCollector#create}).
   *
   * @see #merge(Sort, int, int, TopFieldDocs[])
   * @lucene.experimental
   */
  public static TopFieldDocs merge(Sort sort, int topN, TopFieldDocs[] shardHits) {
    return merge(sort, 0, topN, shardHits);
  }

  /**
   * Same as {@link #merge(Sort, int, TopFieldDocs[])} but also ignores the top {@code start} top
   * docs. This is typically useful for pagination.
   *
   * <p>docIDs are expected to be in consistent pattern i.e. either all ScoreDocs have their
   * shardIndex set, or all have them as -1 (signifying that all hits belong to same searcher)
   *
   * @lucene.experimental
   */
  public static TopFieldDocs merge(Sort sort, int start, int topN, TopFieldDocs[] shardHits) {
    if (sort == null) {
      throw new IllegalArgumentException("sort must be non-null when merging field-docs");
    }
    return (TopFieldDocs) mergeAux(sort, start, topN, shardHits, DEFAULT_TIE_BREAKER);
  }

  /**
   * Pass in a custom tie breaker for ordering results
   *
   * @lucene.experimental
   */
  public static TopFieldDocs merge(
      Sort sort, int start, int topN, TopFieldDocs[] shardHits, Comparator<ScoreDoc> tieBreaker) {
    if (sort == null) {
      throw new IllegalArgumentException("sort must be non-null when merging field-docs");
    }
    return (TopFieldDocs) mergeAux(sort, start, topN, shardHits, tieBreaker);
  }

  /**
   * Auxiliary method used by the {@link #merge} impls. A sort value of null is used to indicate
   * that docs should be sorted by score.
   */
  private static TopDocs mergeAux(
      Sort sort, int start, int size, TopDocs[] shardHits, Comparator<ScoreDoc> tieBreaker) {

    // 优先级队列，每个元素就是当前的每个shard结果，在里面重写了这些shard在小堆顶的排序（比较）
    // 支持多级字段比较
    // 队列里shard的实时排序是排每个shard当前指向的doc（每个shard结果都有多个doc）
    final PriorityQueue<ShardRef> queue;
    if (sort == null) {
      queue = new ScoreMergeSortQueue(shardHits, tieBreaker);
    } else {
      queue = new MergeSortQueue(sort, shardHits, tieBreaker);
    }

    long totalHitCount = 0;
    TotalHits.Relation totalHitsRelation = TotalHits.Relation.EQUAL_TO;
    int availHitCount = 0;
    for (int shardIDX = 0; shardIDX < shardHits.length; shardIDX++) {
      final TopDocs shard = shardHits[shardIDX];
      // totalHits can be non-zero even if no hits were
      // collected, when searchAfter was used:
      totalHitCount += shard.totalHits.value;
      // If any hit count is a lower bound then the merged
      // total hit count is a lower bound as well
      if (shard.totalHits.relation == TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO) {
        totalHitsRelation = TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO;
      }
      // 把doc都放入到queue，（会排序的）
      if (shard.scoreDocs != null && shard.scoreDocs.length > 0) {
        availHitCount += shard.scoreDocs.length;
        queue.add(new ShardRef(shardIDX));
      }
    }

    // 具体doc集合
    final ScoreDoc[] hits;
    boolean unsetShardIndex = false;
    // 命中总数《= 开始下标，即无结果返回
    if (availHitCount <= start) {
      hits = new ScoreDoc[0];
    } else {
      // 申请的数组空间
      hits = new ScoreDoc[Math.min(size, availHitCount - start)];
      // 理论上最后一个数据的下标
      int requestedResultWindow = start + size;
      // 实际最后一个数据下标（有可能超过数量上限）
      int numIterOnHits = Math.min(availHitCount, requestedResultWindow);
      int hitUpto = 0;// 循环开始下标（为了排序效果需要从头开始，未排序不可能从topn下标开始拿）
      // 开始遍历queue-》从头遍历
      while (hitUpto < numIterOnHits) {
        assert queue.size() > 0;
        ShardRef ref = queue.top();// 拿到当前最小的doc所在的shard
        // 这里把ShardRef的hitIndex（当前指向doc下标）-1，等于当前最小doc已被取出
        final ScoreDoc hit = shardHits[ref.shardIndex].scoreDocs[ref.hitIndex++];

        // Irrespective of whether we use shard indices for tie breaking or not, we check for
        // consistent
        // order in shard indices to defend against potential bugs
        if (hitUpto > 0) {
          if (unsetShardIndex != (hit.shardIndex == -1)) {
            throw new IllegalArgumentException("Inconsistent order of shard indices");
          }
        }

        unsetShardIndex |= hit.shardIndex == -1;

        // 当前下标在目标start后，才会放入结果hit数组
        if (hitUpto >= start) {
          hits[hitUpto - start] = hit;
        }

        hitUpto++;// +1

        // 当前shard结果的doc还没取完
        if (ref.hitIndex < shardHits[ref.shardIndex].scoreDocs.length) {
          // Not done with this these TopDocs yet:
          // 因为上面把hitIndex-1了，所以更新优先队列里的排序
          queue.updateTop();
        } else {
          // 当前shard结果的doc已用完，可以从队列去除，变相也更新队列排序
          queue.pop();
        }
      }
    }

    TotalHits totalHits = new TotalHits(totalHitCount, totalHitsRelation);
    if (sort == null) {
      return new TopDocs(totalHits, hits);
    } else {
      return new TopFieldDocs(totalHits, hits, sort.getSort());
    }
  }
}

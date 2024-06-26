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

import java.io.IOException;
import java.util.Objects;

import org.apache.lucene.codecs.lucene90.blocktree.FieldReader;
import org.apache.lucene.index.*;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.util.BytesRef;

/**
 * A Query that matches documents containing a term. This may be combined with other terms with a
 * {@link BooleanQuery}.
 */
public class TermQuery extends Query {

  private final Term term;
  private final TermStates perReaderTermState;

  final class TermWeight extends Weight {
    private final Similarity similarity;
    private final Similarity.SimScorer simScorer; // 计分器
    private final TermStates termStates;// 目标词的统计信息
    private final ScoreMode scoreMode;

    public TermWeight(
        IndexSearcher searcher, ScoreMode scoreMode, float boost, TermStates termStates)
        throws IOException {
      super(TermQuery.this);
      if (scoreMode.needsScores() && termStates == null) {
        throw new IllegalStateException("termStates are required when scores are needed");
      }
      this.scoreMode = scoreMode;
      this.termStates = termStates;// 查询值在字段索引的信息
      this.similarity = searcher.getSimilarity();

      // Stats -》单纯数据统计信息，非内容
      final CollectionStatistics collectionStats;
      final TermStatistics termStats;
      // 其实等于前面已经做完检索相关？
      if (scoreMode.needsScores()) {
        // 这个通过searcher拿到目标index中这个字段索引总体信息（）
        collectionStats = searcher.collectionStatistics(term.field());// 查询字段名
        // 当前值的在对应字段索引的信息
        // 这里还判断是否有符合的doc，无就null
        termStats =
            termStates.docFreq() > 0
                ? searcher.termStatistics(term, termStates.docFreq(), termStates.totalTermFreq())// 包装值字节流、值的df（出现在多少个doc）、值的总tf （所有doc下出现次数）
                : null;
      } else {
        // we do not need the actual stats, use fake stats with docFreq=maxDoc=ttf=1
        // 查询字段名
        collectionStats = new CollectionStatistics(term.field(), 1, 1, 1, 1);
        termStats = new TermStatistics(term.bytes(), 1, 1);// 查询值
      }

      if (termStats == null) {
        // 代表无符合doc，直接null

        this.simScorer = null; // term doesn't exist in any segment, we won't use similarity at all
      } else {
        // 有符合doc

        // 相似度计分器
        // collectionStats只入-》只是当前字段的统计信息
        // 把Stats提供给计分器做算分使用
        this.simScorer = similarity.scorer(boost, collectionStats, termStats);
      }
    }

    @Override
    public Matches matches(LeafReaderContext context, int doc) throws IOException {
      TermsEnum te = getTermsEnum(context);
      if (te == null) {
        return null;
      }
      return MatchesUtils.forField(
          term.field(),
          () -> {
            PostingsEnum pe = te.postings(null, PostingsEnum.OFFSETS);
            if (pe.advance(doc) != doc) {
              return null;
            }
            return new TermMatchesIterator(getQuery(), pe);
          });
    }

    @Override
    public String toString() {
      return "weight(" + TermQuery.this + ")";
    }

    @Override
    public Scorer scorer(LeafReaderContext context) throws IOException {
      assert termStates == null || termStates.wasBuiltFor(ReaderUtil.getTopLevelContext(context))
          : "The top-reader used to create Weight is not the same as the current reader's top-reader ("
              + ReaderUtil.getTopLevelContext(context);
      ;
      /**
       * 生成当前segment下当前field的词迭代器（加载fst索引），并在里面检索到目标term的倒排索引内容
       */

      // 获取倒排索引，并进行检索
      final TermsEnum termsEnum = getTermsEnum(context);

      // 无倒排索引
      // 就是说当前segment（LeafReaderContext） 没有做这个field的倒排索引（无tip、tim）
      if (termsEnum == null) {
        return null;
      }


      // 包装基本当前term query的scorer
      LeafSimScorer scorer =
          new LeafSimScorer(simScorer, context.reader(), term.field(), scoreMode.needsScores());
      // 一般是这个，除非全量
      if (scoreMode == ScoreMode.TOP_SCORES) {
        // 再对外包装个scorer （TermScorer -> LeafSimScorer -> 相似度底层simSimScorer）
        return new TermScorer(this, termsEnum.impacts(PostingsEnum.FREQS), scorer);
      } else {
        return new TermScorer(
            this,
            termsEnum.postings(
                null, scoreMode.needsScores() ? PostingsEnum.FREQS : PostingsEnum.NONE),
            scorer);
      }
    }

    @Override
    public boolean isCacheable(LeafReaderContext ctx) {
      return true;
    }

    /**
     * Returns a {@link TermsEnum} positioned at this weights Term or null if the term does not
     * exist in the given context
     */
    private TermsEnum getTermsEnum(LeafReaderContext context) throws IOException {
      // 这个方法等于获取词典

      assert termStates != null;
      assert termStates.wasBuiltFor(ReaderUtil.getTopLevelContext(context))
          : "The top-reader used to create Weight is not the same as the current reader's top-reader ("
              + ReaderUtil.getTopLevelContext(context);

      // 找到当前segment对应的TermState
      final TermState state = termStates.get(context);
      if (state == null) { // term is not present in that reader
        assert termNotInReader(context.reader(), term)
            : "no termstate found but term exists in reader term=" + term;
        return null;
      }


      /**
       * SegmentReader实际使用 , 获取当前segment下这个field的倒排索引reader
       * @see CodecReader#terms(String)
       *
       * @see FieldReader#iterator()
       */
      // 加载获取当前segment下这个field的倒排索引reader
      // 这个迭代器可以遍历这个segment下这个feild倒排索引的所有term词
      // SegmentTermsEnum
      final TermsEnum termsEnum = context.reader().terms(term.field()).iterator();

      // 检索目标值
      /**
       * 实际就是把 term（blob）跟state放入到TermsEnum中，实际没做检索操作
       * @see org.apache.lucene.codecs.blockterms.BlockTermsReader.FieldReader.SegmentTermsEnum#seekExact(BytesRef, TermState)
       */
      termsEnum.seekExact(term.bytes(), state);
      return termsEnum;
    }

    private boolean termNotInReader(LeafReader reader, Term term) throws IOException {
      // only called from assert
      // System.out.println("TQ.termNotInReader reader=" + reader + " term=" +
      // field + ":" + bytes.utf8ToString());
      return reader.docFreq(term) == 0;
    }

    @Override
    public Explanation explain(LeafReaderContext context, int doc) throws IOException {
      TermScorer scorer = (TermScorer) scorer(context);
      if (scorer != null) {
        int newDoc = scorer.iterator().advance(doc);
        if (newDoc == doc) {
          float freq = scorer.freq();
          LeafSimScorer docScorer =
              new LeafSimScorer(simScorer, context.reader(), term.field(), true);
          Explanation freqExplanation =
              Explanation.match(freq, "freq, occurrences of term within document");
          Explanation scoreExplanation = docScorer.explain(doc, freqExplanation);
          return Explanation.match(
              scoreExplanation.getValue(),
              "weight("
                  + getQuery()
                  + " in "
                  + doc
                  + ") ["
                  + similarity.getClass().getSimpleName()
                  + "], result of:",
              scoreExplanation);
        }
      }
      return Explanation.noMatch("no matching term");
    }

    @Override
    public int count(LeafReaderContext context) throws IOException {
      if (context.reader().hasDeletions() == false) {
        TermsEnum termsEnum = getTermsEnum(context);
        // termsEnum is not null if term state is available
        if (termsEnum != null) {
          return termsEnum.docFreq();
        } else {
          // the term cannot be found in the dictionary so the count is 0
          return 0;
        }
      } else {
        return super.count(context);
      }
    }
  }

  /** Constructs a query for the term <code>t</code>. */
  public TermQuery(Term t) {
    // term 里包含字段名、查询值
    term = Objects.requireNonNull(t);
    perReaderTermState = null;
  }

  /**
   * Expert: constructs a TermQuery that will use the provided docFreq instead of looking up the
   * docFreq against the searcher.
   */
  public TermQuery(Term t, TermStates states) {
    assert states != null;
    term = Objects.requireNonNull(t);
    perReaderTermState = Objects.requireNonNull(states);
  }

  /** Returns the term of this query. */
  public Term getTerm() {
    return term;
  }

  @Override
  public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost)
      throws IOException {
    // 生成1个TermStates
    final IndexReaderContext context = searcher.getTopReaderContext();
    final TermStates termState;
    if (perReaderTermState == null || perReaderTermState.wasBuiltFor(context) == false) {
      // 构建TermStates！！！ -> 这里可能做对应term的检索
      termState = TermStates.build(context, term, scoreMode.needsScores());
    } else {
      // PRTS was pre-build for this IS
      termState = this.perReaderTermState;
    }

    // 这里主要是封装字段索引、查询值在索引、计分器等东西，看着进入前已做完检索！！！
    // 包装成1Weight，主要是termState(就是查询词在字段索引的检索信息)，其他都是原来入参
    return new TermWeight(searcher, scoreMode, boost, termState);
  }

  @Override
  public void visit(QueryVisitor visitor) {
    if (visitor.acceptField(term.field())) {
      visitor.consumeTerms(this, term);
    }
  }

  /** Prints a user-readable version of this query. */
  @Override
  public String toString(String field) {
    StringBuilder buffer = new StringBuilder();
    if (!term.field().equals(field)) {
      buffer.append(term.field());
      buffer.append(":");
    }
    buffer.append(term.text());
    return buffer.toString();
  }

  /**
   * Returns the {@link TermStates} passed to the constructor, or null if it was not passed.
   *
   * @lucene.experimental
   */
  public TermStates getTermStates() {
    return perReaderTermState;
  }

  /** Returns true iff <code>other</code> is equal to <code>this</code>. */
  @Override
  public boolean equals(Object other) {
    return sameClassAs(other) && term.equals(((TermQuery) other).term);
  }

  @Override
  public int hashCode() {
    return classHash() ^ term.hashCode();
  }
}

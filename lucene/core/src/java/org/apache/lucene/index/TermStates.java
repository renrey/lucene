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
package org.apache.lucene.index;

import org.apache.lucene.codecs.lucene90.blocktree.FieldReader;
import org.apache.lucene.codecs.lucene90.blocktree.SegmentTermsEnum;
import org.apache.lucene.util.BytesRef;

import java.io.IOException;
import java.util.Arrays;

/**
 * Maintains a {@link IndexReader} {@link TermState} view over {@link IndexReader} instances
 * containing a single term. The {@link TermStates} doesn't track if the given {@link TermState}
 * objects are valid, neither if the {@link TermState} instances refer to the same terms in the
 * associated readers.
 *
 * @lucene.experimental
 */
public final class TermStates {

  private static final TermState EMPTY_TERMSTATE =
      new TermState() {
        @Override
        public void copyFrom(TermState other) {}
      };

  // Important: do NOT keep hard references to index readers
  private final Object topReaderContextIdentity;
  private final TermState[] states;
  private final Term term; // null if stats are to be used
  private int docFreq;
  private long totalTermFreq;

  // public static boolean DEBUG = BlockTreeTermsWriter.DEBUG;

  private TermStates(Term term, IndexReaderContext context) {
    assert context != null && context.isTopLevel;
    topReaderContextIdentity = context.identity;
    docFreq = 0;
    totalTermFreq = 0;
    final int len;
    if (context.leaves() == null) {
      len = 1;
    } else {
      len = context.leaves().size();
    }
    states = new TermState[len];
    this.term = term;
  }

  /** Creates an empty {@link TermStates} from a {@link IndexReaderContext} */
  public TermStates(IndexReaderContext context) {
    this(null, context);
  }

  /**
   * Expert: Return whether this {@link TermStates} was built for the given {@link
   * IndexReaderContext}. This is typically used for assertions.
   *
   * @lucene.internal
   */
  public boolean wasBuiltFor(IndexReaderContext context) {
    return topReaderContextIdentity == context.identity;
  }

  /** Creates a {@link TermStates} with an initial {@link TermState}, {@link IndexReader} pair. */
  public TermStates(
      IndexReaderContext context, TermState state, int ord, int docFreq, long totalTermFreq) {
    this(null, context);
    register(state, ord, docFreq, totalTermFreq);
  }

  /**
   * Creates a {@link TermStates} from a top-level {@link IndexReaderContext} and the given {@link
   * Term}. This method will lookup the given term in all context's leaf readers and register each
   * of the readers containing the term in the returned {@link TermStates} using the leaf reader's
   * ordinal.
   *
   * <p>Note: the given context must be a top-level context.
   *
   * @param needsStats if {@code true} then all leaf contexts will be visited up-front to collect
   *     term statistics. Otherwise, the {@link TermState} objects will be built only when requested
   */
  public static TermStates build(IndexReaderContext context, Term term, boolean needsStats)
      throws IOException {
    assert context != null && context.isTopLevel;
    final TermStates perReaderTermState = new TermStates(needsStats ? null : term, context);
    if (needsStats) {
      // 遍历segment
      for (final LeafReaderContext ctx : context.leaves()) {
        // if (DEBUG) System.out.println("  r=" + leaves[i].reader);
        /**
         *  加载terms!!! 加载字段的词典并且做 检索，无目标词则返回空
         */
        TermsEnum termsEnum = loadTermsEnum(ctx, term);
        // 当前segment 有这个字段的词典，并且有符合检索的词
        if (termsEnum != null) {
          // 大概就是从词典对象拷贝符合的词信息（因为是迭代器，所以最后的就是目标） -》而这里就是1个词，所以迭代器只用1次
          final TermState termState = termsEnum.termState();
          // if (DEBUG) System.out.println("    found");
          // 把当前segment的 符合词（1个）信息注册到perReaderTermState
          // 参数：termState-- 当前segment下对应词信息、ord--当前segment的顺序 、 docFreq-- 当前字段索引的doc数、totalTermFreq--当前字段索引tf总数
          perReaderTermState.register(
              termState, ctx.ord, termsEnum.docFreq(), termsEnum.totalTermFreq());
        }
      }
    }
    return perReaderTermState;
  }

  private static TermsEnum loadTermsEnum(LeafReaderContext ctx, Term term) throws IOException {
    // 拿到当前field的FeildReader -》从当前segment获取当前字段的词典
    // Terms 代表多个包含多个term的集合，这里就是代表（当前segment-ctx）1个field下的term
    final Terms terms = ctx.reader().terms(term.field());
    // 有存在这个字段的词典
    if (terms != null) {
      // 生成迭代器SegmentTermsEnum
      /**
       * @see FieldReader#iterator()
       */
      final TermsEnum termsEnum = terms.iterator();// 词典迭代器
      /**
       * 通过TermsEnum迭代器找到目标term
       * @see SegmentTermsEnum#seekExact(BytesRef)
       */
      // 查找当前term的位置 -》通过这个字段的词典迭代器检索
      if (termsEnum.seekExact(term.bytes())) {// term.bytes()-》查询词的字节流
        // 当前segment的这个字段词典有这个词，返回词典？
        return termsEnum;
      }
    }
    // 无这个字段的词典、当前segment的字段词典无符合的term 都返回null
    return null;
  }

  /** Clears the {@link TermStates} internal state and removes all registered {@link TermState}s */
  public void clear() {
    docFreq = 0;
    totalTermFreq = 0;
    Arrays.fill(states, null);
  }

  /**
   * Registers and associates a {@link TermState} with an leaf ordinal. The leaf ordinal should be
   * derived from a {@link IndexReaderContext}'s leaf ord.
   */
  public void register(
      TermState state, final int ord, final int docFreq, final long totalTermFreq) {
    // 把词信息对象保存到数组
    register(state, ord);
    // 直接累加 df、tf
    accumulateStatistics(docFreq, totalTermFreq);
  }

  /**
   * Expert: Registers and associates a {@link TermState} with an leaf ordinal. The leaf ordinal
   * should be derived from a {@link IndexReaderContext}'s leaf ord. On the contrary to {@link
   * #register(TermState, int, int, long)} this method does NOT update term statistics.
   */
  public void register(TermState state, final int ord) {
    assert state != null : "state must not be null";
    assert ord >= 0 && ord < states.length;
    assert states[ord] == null : "state for ord: " + ord + " already registered";
    // 数组还是以segment编号，如seg无结果照占着
    states[ord] = state;
  }

  /** Expert: Accumulate term statistics. */
  public void accumulateStatistics(final int docFreq, final long totalTermFreq) {
    assert docFreq >= 0;
    assert totalTermFreq >= 0;
    assert docFreq <= totalTermFreq;
    this.docFreq += docFreq;
    this.totalTermFreq += totalTermFreq;
  }

  /**
   * Returns the {@link TermState} for a leaf reader context or <code>null</code> if no {@link
   * TermState} for the context was registered.
   *
   * @param ctx the {@link LeafReaderContext} to get the {@link TermState} for.
   * @return the {@link TermState} for the given readers ord or <code>null</code> if no {@link
   *     TermState} for the reader was registered
   */
  public TermState get(LeafReaderContext ctx) throws IOException {
    assert ctx.ord >= 0 && ctx.ord < states.length;
    if (term == null) return states[ctx.ord];
    if (this.states[ctx.ord] == null) {
      TermsEnum te = loadTermsEnum(ctx, term);
      this.states[ctx.ord] = te == null ? EMPTY_TERMSTATE : te.termState();
    }
    if (this.states[ctx.ord] == EMPTY_TERMSTATE) return null;
    return this.states[ctx.ord];
  }

  /**
   * Returns the accumulated document frequency of all {@link TermState} instances passed to {@link
   * #register(TermState, int, int, long)}.
   *
   * @return the accumulated document frequency of all {@link TermState} instances passed to {@link
   *     #register(TermState, int, int, long)}.
   */
  public int docFreq() {
    if (term != null) {
      throw new IllegalStateException("Cannot call docFreq() when needsStats=false");
    }
    return docFreq;
  }

  /**
   * Returns the accumulated term frequency of all {@link TermState} instances passed to {@link
   * #register(TermState, int, int, long)}.
   *
   * @return the accumulated term frequency of all {@link TermState} instances passed to {@link
   *     #register(TermState, int, int, long)}.
   */
  public long totalTermFreq() {
    if (term != null) {
      throw new IllegalStateException("Cannot call totalTermFreq() when needsStats=false");
    }
    return totalTermFreq;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("TermStates\n");
    for (TermState termState : states) {
      sb.append("  state=");
      sb.append(termState);
      sb.append('\n');
    }

    return sb.toString();
  }
}

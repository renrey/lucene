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
package org.apache.lucene.codecs.lucene90.blocktree;

import java.io.IOException;
import java.util.Arrays;
import org.apache.lucene.codecs.BlockTermState;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.TermsEnum.SeekStatus;
import org.apache.lucene.store.ByteArrayDataInput;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.fst.FST;

final class SegmentTermsEnumFrame {
  // Our index in stack[]:
  final int ord;

  boolean hasTerms;
  boolean hasTermsOrig;
  boolean isFloor;

  FST.Arc<BytesRef> arc;

  // static boolean DEBUG = BlockTreeTermsWriter.DEBUG;

  // File pointer where this block was loaded from
  long fp;
  long fpOrig;
  long fpEnd;
  long totalSuffixBytes; // for stats

  byte[] suffixBytes = new byte[128];
  final ByteArrayDataInput suffixesReader = new ByteArrayDataInput();

  byte[] suffixLengthBytes;
  final ByteArrayDataInput suffixLengthsReader;

  byte[] statBytes = new byte[64];
  int statsSingletonRunLength = 0;
  final ByteArrayDataInput statsReader = new ByteArrayDataInput();

  byte[] floorData = new byte[32];
  final ByteArrayDataInput floorDataReader = new ByteArrayDataInput();

  // Length of prefix shared by all terms in this block
  int prefix;

  // Number of entries (term or sub-block) in this block
  int entCount;

  // Which term we will next read, or -1 if the block
  // isn't loaded yet
  int nextEnt;

  // True if this block is either not a floor block,
  // or, it's the last sub-block of a floor block
  boolean isLastInFloor;

  // True if all entries are terms
  boolean isLeafBlock;

  long lastSubFP;

  int nextFloorLabel;
  int numFollowFloorBlocks;

  // Next term to decode metaData; we decode metaData
  // lazily so that scanning to find the matching term is
  // fast and only if you find a match and app wants the
  // stats or docs/positions enums, will we decode the
  // metaData
  int metaDataUpto;

  final BlockTermState state;

  // metadata buffer
  byte[] bytes = new byte[32];
  final ByteArrayDataInput bytesReader = new ByteArrayDataInput();

  private final SegmentTermsEnum ste;

  public SegmentTermsEnumFrame(SegmentTermsEnum ste, int ord) throws IOException {
    this.ste = ste;
    this.ord = ord;
    this.state = ste.fr.parent.postingsReader.newTermState();
    this.state.totalTermFreq = -1;
    suffixLengthBytes = new byte[32];
    suffixLengthsReader = new ByteArrayDataInput();
  }

  public void setFloorData(ByteArrayDataInput in, BytesRef source) {
    final int numBytes = source.length - (in.getPosition() - source.offset);
    if (numBytes > floorData.length) {
      floorData = new byte[ArrayUtil.oversize(numBytes, 1)];
    }
    System.arraycopy(source.bytes, source.offset + in.getPosition(), floorData, 0, numBytes);
    floorDataReader.reset(floorData, 0, numBytes);
    numFollowFloorBlocks = floorDataReader.readVInt();
    nextFloorLabel = floorDataReader.readByte() & 0xff;
    // if (DEBUG) {
    // System.out.println("    setFloorData fpOrig=" + fpOrig + " bytes=" + new
    // BytesRef(source.bytes, source.offset + in.getPosition(), numBytes) + " numFollowFloorBlocks="
    // + numFollowFloorBlocks + " nextFloorLabel=" + toHex(nextFloorLabel));
    // }
  }

  public int getTermBlockOrd() {
    return isLeafBlock ? nextEnt : state.termBlockOrd;
  }

  void loadNextFloorBlock() throws IOException {
    // if (DEBUG) {
    // System.out.println("    loadNextFloorBlock fp=" + fp + " fpEnd=" + fpEnd);
    // }
    assert arc == null || isFloor : "arc=" + arc + " isFloor=" + isFloor;
    fp = fpEnd;
    nextEnt = -1;
    loadBlock();
  }

  /* Does initial decode of next block of terms; this
  doesn't actually decode the docFreq, totalTermFreq,
  postings details (frq/prx offset, etc.) metadata;
  it just loads them as byte[] blobs which are then
  decoded on-demand if the metadata is ever requested
  for any term in this block.  This enables terms-only
  intensive consumes (eg certain MTQs, respelling) to
  not pay the price of decoding metadata they won't
  use. */
  void loadBlock() throws IOException {

    // Clone the IndexInput lazily, so that consumers
    // that just pull a TermsEnum to
    // seekExact(TermState) don't pay this cost:
    // 初始化-- ste里的index文件（tib）内容---》克隆
    ste.initIndexInput();

    if (nextEnt != -1) {
      // Already loaded
      return;
    }
    // System.out.println("blc=" + blockLoadCount);

    ste.in.seek(fp);// 查找
    // 读取entCount
    int code = ste.in.readVInt();
    entCount = code >>> 1; // 底1bit有其他作用
    assert entCount > 0;
    // 底1bit就是判断是否floor的最后，1：是
    isLastInFloor = (code & 1) != 0;

    assert arc == null || (isLastInFloor || isFloor)
        : "fp=" + fp + " arc=" + arc + " isFloor=" + isFloor + " isLastInFloor=" + isLastInFloor;

    // TODO: if suffixes were stored in random-access
    // array structure, then we could do binary search
    // instead of linear scan to find target term; eg
    // we could have simple array of offsets

    // 当前fp就是suffix的开始
    final long startSuffixFP = ste.in.getFilePointer();
    // term suffixes:
    final long codeL = ste.in.readVLong();// 等于读了当前suffix的信息
    isLeafBlock = (codeL & 0x04) != 0; // 低3位是否=1， 1是叶子block
    // 当前suffix占用字节数（前5位）
    final int numSuffixBytes = (int) (codeL >>> 3);
    // 当前使用的临时suffixBytes大小不满足suffix的字节长度，扩容
    if (suffixBytes.length < numSuffixBytes) {
      suffixBytes = new byte[ArrayUtil.oversize(numSuffixBytes, 1)];
    }
    try {
      compressionAlg = CompressionAlgorithm.byCode((int) codeL & 0x03);
    } catch (IllegalArgumentException e) {
      throw new CorruptIndexException(e.getMessage(), ste.in, e);
    }
    // 放入suffixBytes
    compressionAlg.read(ste.in, suffixBytes, numSuffixBytes);
    suffixesReader.reset(suffixBytes, 0, numSuffixBytes);

    int numSuffixLengthBytes = ste.in.readVInt();
    final boolean allEqual = (numSuffixLengthBytes & 0x01) != 0;
    numSuffixLengthBytes >>>= 1;
    if (suffixLengthBytes.length < numSuffixLengthBytes) {
      suffixLengthBytes = new byte[ArrayUtil.oversize(numSuffixLengthBytes, 1)];
    }
    if (allEqual) {
      Arrays.fill(suffixLengthBytes, 0, numSuffixLengthBytes, ste.in.readByte());
    } else {
      ste.in.readBytes(suffixLengthBytes, 0, numSuffixLengthBytes);
    }
    suffixLengthsReader.reset(suffixLengthBytes, 0, numSuffixLengthBytes);
    totalSuffixBytes = ste.in.getFilePointer() - startSuffixFP;

    /*if (DEBUG) {
    if (arc == null) {
    System.out.println("    loadBlock (next) fp=" + fp + " entCount=" + entCount + " prefixLen=" + prefix + " isLastInFloor=" + isLastInFloor + " leaf?=" + isLeafBlock);
    } else {
    System.out.println("    loadBlock (seek) fp=" + fp + " entCount=" + entCount + " prefixLen=" + prefix + " hasTerms?=" + hasTerms + " isFloor?=" + isFloor + " isLastInFloor=" + isLastInFloor + " leaf?=" + isLeafBlock);
    }
    }*/

    // stats
    int numBytes = ste.in.readVInt();
    if (statBytes.length < numBytes) {
      statBytes = new byte[ArrayUtil.oversize(numBytes, 1)];
    }
    ste.in.readBytes(statBytes, 0, numBytes);
    statsReader.reset(statBytes, 0, numBytes);
    statsSingletonRunLength = 0;
    metaDataUpto = 0;

    state.termBlockOrd = 0;
    nextEnt = 0;
    lastSubFP = -1;

    // TODO: we could skip this if !hasTerms; but
    // that's rare so won't help much
    // metadata
    numBytes = ste.in.readVInt();
    if (bytes.length < numBytes) {
      bytes = new byte[ArrayUtil.oversize(numBytes, 1)];
    }
    ste.in.readBytes(bytes, 0, numBytes);
    bytesReader.reset(bytes, 0, numBytes);

    // Sub-blocks of a single floor block are always
    // written one after another -- tail recurse:
    fpEnd = ste.in.getFilePointer();
    // if (DEBUG) {
    //   System.out.println("      fpEnd=" + fpEnd);
    // }
  }

  void rewind() {

    // Force reload:
    fp = fpOrig;
    nextEnt = -1;
    hasTerms = hasTermsOrig;
    if (isFloor) {
      floorDataReader.rewind();
      numFollowFloorBlocks = floorDataReader.readVInt();
      assert numFollowFloorBlocks > 0;
      nextFloorLabel = floorDataReader.readByte() & 0xff;
    }

    /*
    //System.out.println("rewind");
    // Keeps the block loaded, but rewinds its state:
    if (nextEnt > 0 || fp != fpOrig) {
    if (DEBUG) {
    System.out.println("      rewind frame ord=" + ord + " fpOrig=" + fpOrig + " fp=" + fp + " hasTerms?=" + hasTerms + " isFloor?=" + isFloor + " nextEnt=" + nextEnt + " prefixLen=" + prefix);
    }
    if (fp != fpOrig) {
    fp = fpOrig;
    nextEnt = -1;
    } else {
    nextEnt = 0;
    }
    hasTerms = hasTermsOrig;
    if (isFloor) {
    floorDataReader.rewind();
    numFollowFloorBlocks = floorDataReader.readVInt();
    nextFloorLabel = floorDataReader.readByte() & 0xff;
    }
    assert suffixBytes != null;
    suffixesReader.rewind();
    assert statBytes != null;
    statsReader.rewind();
    metaDataUpto = 0;
    state.termBlockOrd = 0;
    // TODO: skip this if !hasTerms?  Then postings
    // impl wouldn't have to write useless 0 byte
    postingsReader.resetTermsBlock(fieldInfo, state);
    lastSubFP = -1;
    } else if (DEBUG) {
    System.out.println("      skip rewind fp=" + fp + " fpOrig=" + fpOrig + " nextEnt=" + nextEnt + " ord=" + ord);
    }
    */
  }

  // Decodes next entry; returns true if it's a sub-block
  public boolean next() throws IOException {
    if (isLeafBlock) {
      // 叶子block 更新到下个叶子，然后返回false，代表当前分支没有next了
      nextLeaf();
      return false;
    } else {
      // 这个就是一直扫描到叶子
      return nextNonLeaf();
    }
  }

  public void nextLeaf() {
    // if (DEBUG) System.out.println("  frame.next ord=" + ord + " nextEnt=" + nextEnt + "
    // entCount=" + entCount);
    assert nextEnt != -1 && nextEnt < entCount
        : "nextEnt=" + nextEnt + " entCount=" + entCount + " fp=" + fp;
    // nextEnt+1
    nextEnt++;
    suffix = suffixLengthsReader.readVInt();// 读取下一个suffix的长度
    startBytePos = suffixesReader.getPosition();
    ste.term.setLength(prefix + suffix);
    ste.term.grow(ste.term.length());
    // 读取数据放入到suffixesReader-》把当前的suffix内容拷贝到suffixesReader中
    suffixesReader.readBytes(ste.term.bytes(), prefix, suffix);
    ste.termExists = true;
  }

  public boolean nextNonLeaf() throws IOException {
    // if (DEBUG) System.out.println("  stef.next ord=" + ord + " nextEnt=" + nextEnt + " entCount="
    // + entCount + " fp=" + suffixesReader.getPosition());
    while (true) {
      // 需要遍历完
      if (nextEnt == entCount) {
        assert arc == null || (isFloor && isLastInFloor == false)
            : "isFloor=" + isFloor + " isLastInFloor=" + isLastInFloor;
        loadNextFloorBlock();
        // 终止
        if (isLeafBlock) {
          nextLeaf();
          return false;
        } else {
          continue;
        }
      }

      assert nextEnt != -1 && nextEnt < entCount
          : "nextEnt=" + nextEnt + " entCount=" + entCount + " fp=" + fp;
      nextEnt++;
      final int code = suffixLengthsReader.readVInt();
      suffix = code >>> 1;
      startBytePos = suffixesReader.getPosition();
      ste.term.setLength(prefix + suffix);
      ste.term.grow(ste.term.length());
      suffixesReader.readBytes(ste.term.bytes(), prefix, suffix);
      if ((code & 1) == 0) {
        // A normal term
        ste.termExists = true;
        subCode = 0;
        state.termBlockOrd++;
        return false;
      } else {
        // A sub-block; make sub-FP absolute:
        ste.termExists = false;
        subCode = suffixLengthsReader.readVLong();
        lastSubFP = fp - subCode;
        // if (DEBUG) {
        // System.out.println("    lastSubFP=" + lastSubFP);
        // }
        return true;
      }
    }
  }

  // TODO: make this array'd so we can do bin search?
  // likely not worth it?  need to measure how many
  // floor blocks we "typically" get
  public void scanToFloorFrame(BytesRef target) {

    if (!isFloor || target.length <= prefix) {
      // if (DEBUG) {
      //   System.out.println("    scanToFloorFrame skip: isFloor=" + isFloor + " target.length=" +
      // target.length + " vs prefix=" + prefix);
      // }
      return;
    }

    final int targetLabel = target.bytes[target.offset + prefix] & 0xFF;

    // if (DEBUG) {
    //   System.out.println("    scanToFloorFrame fpOrig=" + fpOrig + " targetLabel=" +
    // toHex(targetLabel) + " vs nextFloorLabel=" + toHex(nextFloorLabel) + " numFollowFloorBlocks="
    // + numFollowFloorBlocks);
    // }

    if (targetLabel < nextFloorLabel) {
      // if (DEBUG) {
      //   System.out.println("      already on correct block");
      // }
      return;
    }

    assert numFollowFloorBlocks != 0;

    long newFP = fpOrig;
    while (true) {
      final long code = floorDataReader.readVLong();
      newFP = fpOrig + (code >>> 1);
      hasTerms = (code & 1) != 0;
      // if (DEBUG) {
      //   System.out.println("      label=" + toHex(nextFloorLabel) + " fp=" + newFP + "
      // hasTerms?=" + hasTerms + " numFollowFloor=" + numFollowFloorBlocks);
      // }

      isLastInFloor = numFollowFloorBlocks == 1;
      numFollowFloorBlocks--;

      if (isLastInFloor) {
        nextFloorLabel = 256;
        // if (DEBUG) {
        //   System.out.println("        stop!  last block nextFloorLabel=" +
        // toHex(nextFloorLabel));
        // }
        break;
      } else {
        nextFloorLabel = floorDataReader.readByte() & 0xff;
        if (targetLabel < nextFloorLabel) {
          // if (DEBUG) {
          //   System.out.println("        stop!  nextFloorLabel=" + toHex(nextFloorLabel));
          // }
          break;
        }
      }
    }

    if (newFP != fp) {
      // Force re-load of the block:
      // if (DEBUG) {
      //   System.out.println("      force switch to fp=" + newFP + " oldFP=" + fp);
      // }
      nextEnt = -1;
      fp = newFP;
    } else {
      // if (DEBUG) {
      //   System.out.println("      stay on same fp=" + newFP);
      // }
    }
  }

  public void decodeMetaData() throws IOException {

    // if (DEBUG) System.out.println("\nBTTR.decodeMetadata seg=" + segment + " mdUpto=" +
    // metaDataUpto + " vs termBlockOrd=" + state.termBlockOrd);

    // lazily catch up on metadata decode:
    final int limit = getTermBlockOrd();
    boolean absolute = metaDataUpto == 0;
    assert limit > 0;

    // TODO: better API would be "jump straight to term=N"???
    while (metaDataUpto < limit) {

      // TODO: we could make "tiers" of metadata, ie,
      // decode docFreq/totalTF but don't decode postings
      // metadata; this way caller could get
      // docFreq/totalTF w/o paying decode cost for
      // postings

      // TODO: if docFreq were bulk decoded we could
      // just skipN here:
      if (statsSingletonRunLength > 0) {
        state.docFreq = 1;
        state.totalTermFreq = 1;
        statsSingletonRunLength--;
      } else {
        int token = statsReader.readVInt();
        if ((token & 1) == 1) {
          state.docFreq = 1;
          state.totalTermFreq = 1;
          statsSingletonRunLength = token >>> 1;
        } else {
          state.docFreq = token >>> 1;
          if (ste.fr.fieldInfo.getIndexOptions() == IndexOptions.DOCS) {
            state.totalTermFreq = state.docFreq;
          } else {
            state.totalTermFreq = state.docFreq + statsReader.readVLong();
          }
        }
      }

      // metadata
      ste.fr.parent.postingsReader.decodeTerm(bytesReader, ste.fr.fieldInfo, state, absolute);

      metaDataUpto++;
      absolute = false;
    }
    state.termBlockOrd = metaDataUpto;
  }

  // Used only by assert
  private boolean prefixMatches(BytesRef target) {
    for (int bytePos = 0; bytePos < prefix; bytePos++) {
      if (target.bytes[target.offset + bytePos] != ste.term.byteAt(bytePos)) {
        return false;
      }
    }

    return true;
  }

  // Scans to sub-block that has this target fp; only
  // called by next(); NOTE: does not set
  // startBytePos/suffix as a side effect
  public void scanToSubBlock(long subFP) {
    assert !isLeafBlock;
    // if (DEBUG) System.out.println("  scanToSubBlock fp=" + fp + " subFP=" + subFP + " entCount="
    // + entCount + " lastSubFP=" + lastSubFP);
    // assert nextEnt == 0;
    if (lastSubFP == subFP) {
      // if (DEBUG) System.out.println("    already positioned");
      return;
    }
    assert subFP < fp : "fp=" + fp + " subFP=" + subFP;
    final long targetSubCode = fp - subFP;
    // if (DEBUG) System.out.println("    targetSubCode=" + targetSubCode);
    while (true) {
      assert nextEnt < entCount;
      nextEnt++;
      final int code = suffixLengthsReader.readVInt();
      suffixesReader.skipBytes(code >>> 1);
      if ((code & 1) != 0) {
        final long subCode = suffixLengthsReader.readVLong();
        if (targetSubCode == subCode) {
          // if (DEBUG) System.out.println("        match!");
          lastSubFP = subFP;
          return;
        }
      } else {
        state.termBlockOrd++;
      }
    }
  }

  // NOTE: sets startBytePos/suffix as a side effect
  public SeekStatus scanToTerm(BytesRef target, boolean exactOnly) throws IOException {
    // 叶子block跟非叶子block扫描2种
    return isLeafBlock ? scanToTermLeaf(target, exactOnly) : scanToTermNonLeaf(target, exactOnly);
  }

  private int startBytePos;
  private int suffix;
  private long subCode;
  CompressionAlgorithm compressionAlg = CompressionAlgorithm.NO_COMPRESSION;

  // for debugging
  /*
  @SuppressWarnings("unused")
  static String brToString(BytesRef b) {
    try {
      return b.utf8ToString() + " " + b;
    } catch (Throwable t) {
      // If BytesRef isn't actually UTF8, or it's eg a
      // prefix of UTF8 that ends mid-unicode-char, we
      // fallback to hex:
      return b.toString();
    }
  }
  */

  // Target's prefix matches this block's prefix; we
  // scan the entries check if the suffix matches.
  public SeekStatus scanToTermLeaf(BytesRef target, boolean exactOnly) throws IOException {

    // if (DEBUG) System.out.println("    scanToTermLeaf: block fp=" + fp + " prefix=" + prefix + "
    // nextEnt=" + nextEnt + " (of " + entCount + ") target=" + brToString(target) + " term=" +
    // brToString(term));

    assert nextEnt != -1;

    ste.termExists = true;
    subCode = 0;

    // 下个ent下标 == 总数，即已完成遍历，未找到
    if (nextEnt == entCount) {
      if (exactOnly) {
        fillTerm();
      }
      return SeekStatus.END;
    }

    assert prefixMatches(target);

    // TODO: binary search when all terms have the same length, which is common for ID fields,
    // which are also the most sensitive to lookup performance?
    // Loop over each entry (term or sub-block) in this block:

    // 遍历当前block的ent
    do {
      nextEnt++;

      suffix = suffixLengthsReader.readVInt();// 读取suffix后缀长度
      // suffixLengthsReader保存的是每个suffix的长度，且是每个suffix的长度紧凑一起，给suffixBytes提供找末尾使用

      // if (DEBUG) {
      //   BytesRef suffixBytesRef = new BytesRef();
      //   suffixBytesRef.bytes = suffixBytes;
      //   suffixBytesRef.offset = suffixesReader.getPosition();
      //   suffixBytesRef.length = suffix;
      //   System.out.println("      cycle: term " + (nextEnt-1) + " (of " + entCount + ") suffix="
      // + brToString(suffixBytesRef));
      // }

      // 获取当前suffixes 当前指针的下标（即本次suffix的开始下标）
      startBytePos = suffixesReader.getPosition();
      suffixesReader.skipBytes(suffix);// 即直接跳到下个的开始，为下次循环做为准备

      // suffixesReader 则是实际保存suffix的内容

      // Loop over bytes in the suffix, comparing to the target
      /**
       * 比较的byte数组
       * suffixBytes [startBytePos : startBytePos + suffix ] (即suffix长度)
       * target.bytes [prefix: length] (即term的suffix)
       * 所以实际是比较term的在当前block的prefix下的 suffix（后缀）是否跟 当前prefix的suffix（当前suffixes）是否相同
       */
      final int cmp =
          Arrays.compareUnsigned(
              suffixBytes,
              startBytePos,
              startBytePos + suffix,
              target.bytes,
              target.offset + prefix,
              target.offset + target.length);
      // 小于，证明还能继续（排序是按）
      if (cmp < 0) {
        // Current entry is still before the target;
        // keep scanning
      // 大于，证明不存在，所以返回NOT_FOUND
      } else if (cmp > 0) {
        // Done!  Current entry is after target --
        // return NOT_FOUND:
        fillTerm();

        // if (DEBUG) System.out.println("        not found");
        return SeekStatus.NOT_FOUND;
      } else {
        // Exact match!

        // This cannot be a sub-block because we
        // would have followed the index to this
        // sub-block from the start:

        assert ste.termExists;
        fillTerm();
        // if (DEBUG) System.out.println("        found!");
        // 找到了
        return SeekStatus.FOUND;
      }
    } while (nextEnt < entCount);// 就是需要遍历完当前block

    // It is possible (and OK) that terms index pointed us
    // at this block, but, we scanned the entire block and
    // did not find the term to position to.  This happens
    // when the target is after the last term in the block
    // (but, before the next term in the index).  EG
    // target could be foozzz, and terms index pointed us
    // to the foo* block, but the last term in this block
    // was fooz (and, eg, first term in the next block will
    // bee fop).
    // if (DEBUG) System.out.println("      block end");
    if (exactOnly) {
      fillTerm();
    }

    // TODO: not consistent that in the
    // not-exact case we don't next() into the next
    // frame here
    // 没找到
    return SeekStatus.END;
  }

  // Target's prefix matches this block's prefix; we
  // scan the entries check if the suffix matches.
  public SeekStatus scanToTermNonLeaf(BytesRef target, boolean exactOnly) throws IOException {

    // if (DEBUG) System.out.println("    scanToTermNonLeaf: block fp=" + fp + " prefix=" + prefix +
    // " nextEnt=" + nextEnt + " (of " + entCount + ") target=" + brToString(target) + " term=" +
    // brToString(target));

    assert nextEnt != -1;

    // 是否已扫描全部
    if (nextEnt == entCount) {
      if (exactOnly) {
        fillTerm();
        ste.termExists = subCode == 0;
      }
      return SeekStatus.END;
    }

    assert prefixMatches(target);

    // Loop over each entry (term or sub-block) in this block:
    // 遍历block
    while (nextEnt < entCount) {

      nextEnt++;

      final int code = suffixLengthsReader.readVInt();// 读取suffix长度
      suffix = code >>> 1;// 尾部的1bit有其他用

      // if (DEBUG) {
      //  BytesRef suffixBytesRef = new BytesRef();
      //  suffixBytesRef.bytes = suffixBytes;
      //  suffixBytesRef.offset = suffixesReader.getPosition();
      //  suffixBytesRef.length = suffix;
      //  System.out.println("      cycle: " + ((code&1)==1 ? "sub-block" : "term") + " " +
      // (nextEnt-1) + " (of " + entCount + ") suffix=" + brToString(suffixBytesRef));
      // }

      // 当前遍历到suffix的整个term长度
      final int termLen = prefix + suffix;
      startBytePos = suffixesReader.getPosition();// 拿到当前suffix的开始下标
      suffixesReader.skipBytes(suffix);//  把下标指针skip到suffix大小，到下个suffix的开始
      // suffixLengths中底1bit的用途：判断term是否存在
      ste.termExists = (code & 1) == 0;

      if (ste.termExists) {
        // 存在
        // termBlockOrd 当前block上的顺序+1，subCode=0
        state.termBlockOrd++;
        subCode = 0;
      } else {
        // suffixLength的下个是subCode
        subCode = suffixLengthsReader.readVLong();
        lastSubFP = fp - subCode;
      }

      // 数组比较：是否suffix一样
      final int cmp =
          Arrays.compareUnsigned(
              suffixBytes,
              startBytePos,
              startBytePos + suffix,
              target.bytes,
              target.offset + prefix,
              target.offset + target.length);

      // 小于继续遍历（suffix按照顺序排序）
      if (cmp < 0) {
        // Current entry is still before the target;
        // keep scanning
      // 大于，不存在
      } else if (cmp > 0) {
        // Done!  Current entry is after target --
        // return NOT_FOUND:
        // 终止循环，需要把ste更新成当前term（prefix+suffix）
        fillTerm();

        // if (DEBUG) System.out.println("        maybe done exactOnly=" + exactOnly + "
        // ste.termExists=" + ste.termExists);

        if (!exactOnly && !ste.termExists) {
          // System.out.println("  now pushFrame");
          // TODO this
          // We are on a sub-block, and caller wants
          // us to position to the next term after
          // the target, so we must recurse into the
          // sub-frame(s):
          // 新增frame，并更新成当前frame
          ste.currentFrame = ste.pushFrame(null, ste.currentFrame.lastSubFP, termLen);
          // 新frame加载block
          ste.currentFrame.loadBlock();
          // 看当前frame下面还有其他block（如果当前叶子block直接返回false，不会继续）
          // 就是把当前分支的frame的block都加载进去
          while (ste.currentFrame.next()) {
            // 下面子block生成frame，并放入stack，然后加载block
            ste.currentFrame = ste.pushFrame(null, ste.currentFrame.lastSubFP, ste.term.length());
            ste.currentFrame.loadBlock();
          }
        }

        // if (DEBUG) System.out.println("        not found");
        return SeekStatus.NOT_FOUND;
      } else {
        // Exact match!

        // This cannot be a sub-block because we
        // would have followed the index to this
        // sub-block from the start:

        assert ste.termExists;
        // 终止循环，需要把ste更新成当前term
        fillTerm();
        // if (DEBUG) System.out.println("        found!");
        return SeekStatus.FOUND;
      }
    }

    // It is possible (and OK) that terms index pointed us
    // at this block, but, we scanned the entire block and
    // did not find the term to position to.  This happens
    // when the target is after the last term in the block
    // (but, before the next term in the index).  EG
    // target could be foozzz, and terms index pointed us
    // to the foo* block, but the last term in this block
    // was fooz (and, eg, first term in the next block will
    // bee fop).
    // if (DEBUG) System.out.println("      block end");
    if (exactOnly) {
      fillTerm();
    }

    // TODO: not consistent that in the
    // not-exact case we don't next() into the next
    // frame here
    // 扫描完没找到
    return SeekStatus.END;
  }

  private void fillTerm() {
    // term长度变使用当前suffix的term完整长度 -》 ste.term变成这个prefix+suffix
    final int termLength = prefix + suffix;
    ste.term.setLength(termLength);// 长度设置
    ste.term.grow(termLength);// byte数组扩容
    // 把这个prefix+suffix的term 内容（字节数组）拷贝到 ste.term的数组
    System.arraycopy(suffixBytes, startBytePos, ste.term.bytes(), prefix, suffix);
  }
}

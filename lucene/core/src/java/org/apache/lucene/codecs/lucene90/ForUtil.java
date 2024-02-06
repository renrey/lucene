// This file has been automatically generated, DO NOT EDIT

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
package org.apache.lucene.codecs.lucene90;

import java.io.IOException;
import org.apache.lucene.store.DataInput;
import org.apache.lucene.store.DataOutput;

// Inspired from https://fulmicoton.com/posts/bitpacking/
// Encodes multiple integers in a long to get SIMD-like speedups.
// If bitsPerValue <= 8 then we pack 8 ints per long
// else if bitsPerValue <= 16 we pack 4 ints per long
// else we pack 2 ints per long

/**
 * frame of reference 实现
 */
final class ForUtil {

  static final int BLOCK_SIZE = 128;
  private static final int BLOCK_SIZE_LOG2 = 7;

  private static long expandMask32(long mask32) {
    // 使用后32位，填充前32位
    return mask32 | (mask32 << 32);
  }

  private static long expandMask16(long mask16) {
    // 把mask16低16位填充到16到31（16bit）
    // 在这32位填充到高32位，得到64位
    return expandMask32(mask16 | (mask16 << 16));
  }

  private static long expandMask8(long mask8) {
    // 就是这8位往前填充，得到8个8位-》64位
    return expandMask16(mask8 | (mask8 << 8));
  }

  private static long mask32(int bitsPerValue) {
    return expandMask32((1L << bitsPerValue) - 1);
  }

  private static long mask16(int bitsPerValue) {
    return expandMask16((1L << bitsPerValue) - 1);
  }

  private static long mask8(int bitsPerValue) {
    // (1L << bitsPerValue) - 1: 低bitsPerValue位全是1
    // 把低8位填充满这64位
    return expandMask8((1L << bitsPerValue) - 1);
  }

  private static void expand8(long[] arr) {
    for (int i = 0; i < 16; ++i) {
      long l = arr[i];
      arr[i] = (l >>> 56) & 0xFFL;
      arr[16 + i] = (l >>> 48) & 0xFFL;
      arr[32 + i] = (l >>> 40) & 0xFFL;
      arr[48 + i] = (l >>> 32) & 0xFFL;
      arr[64 + i] = (l >>> 24) & 0xFFL;
      arr[80 + i] = (l >>> 16) & 0xFFL;
      arr[96 + i] = (l >>> 8) & 0xFFL;
      arr[112 + i] = l & 0xFFL;
    }
  }

  private static void expand8To32(long[] arr) {
    for (int i = 0; i < 16; ++i) {
      long l = arr[i];
      arr[i] = (l >>> 24) & 0x000000FF000000FFL;
      arr[16 + i] = (l >>> 16) & 0x000000FF000000FFL;
      arr[32 + i] = (l >>> 8) & 0x000000FF000000FFL;
      arr[48 + i] = l & 0x000000FF000000FFL;
    }
  }

  // 8bit时是压缩
  private static void collapse8(long[] arr) {
    // 所以把原来的连续顺序的元素分成了8段,即1个long容纳的数，等于需要对这个数组分的段数
    // 最后变成16个long，所以16次
    for (int i = 0; i < 16; ++i) {
      // 通过左移得到原来的低8位然后or在一起

      // 新的long的下标代表在原来顺序段（8个连续）的下标（第n个）
      // 8bit 在新long的下标，是对应段下标
      // 1维：段中下标 2维：段编号
      arr[i] =
          (arr[i] << 56)
              | (arr[16 + i] << 48)
              | (arr[32 + i] << 40)
              | (arr[48 + i] << 32)
              | (arr[64 + i] << 24)
              | (arr[80 + i] << 16)
              | (arr[96 + i] << 8)
              | arr[112 + i];
    }
  }

  private static void expand16(long[] arr) {
    // 32个long 1个long包含4个数字
    for (int i = 0; i < 32; ++i) {
      // 当前新范围的4个取原来的低16位
      long l = arr[i];
      arr[i] = (l >>> 48) & 0xFFFFL;// 保留
      arr[32 + i] = (l >>> 32) & 0xFFFFL;
      arr[64 + i] = (l >>> 16) & 0xFFFFL;
      arr[96 + i] = l & 0xFFFFL;
    }
  }

  private static void expand16To32(long[] arr) {
    for (int i = 0; i < 32; ++i) {
      long l = arr[i];
      arr[i] = (l >>> 16) & 0x0000FFFF0000FFFFL;
      arr[32 + i] = l & 0x0000FFFF0000FFFFL;
    }
  }

  private static void collapse16(long[] arr) {
    // 32个long 1个4数，跟上面8bit的，分为1个long分成4段
    for (int i = 0; i < 32; ++i) {
      arr[i] = (arr[i] << 48) | (arr[32 + i] << 32) | (arr[64 + i] << 16) | arr[96 + i];
    }
  }

  private static void expand32(long[] arr) {
    for (int i = 0; i < 64; ++i) {
      long l = arr[i];
      arr[i] = l >>> 32;
      arr[64 + i] = l & 0xFFFFFFFFL;
    }
  }

  private static void collapse32(long[] arr) {
    // 2段
    for (int i = 0; i < 64; ++i) {
      arr[i] = (arr[i] << 32) | arr[64 + i];
    }
  }

  private final long[] tmp = new long[BLOCK_SIZE / 2];

  /** Encode 128 integers from {@code longs} into {@code out}. */
  void encode(long[] longs, int bitsPerValue, DataOutput out) throws IOException {
    final int nextPrimitive;
    final int  numLongs;// 需要用到的long 数
    // 8bit以内 使用1byte即可
    if (bitsPerValue <= 8) {
      nextPrimitive = 8; // 固定8bit间隔
      numLongs = BLOCK_SIZE / 8; // 128个元素要用16个long
      collapse8(longs); // 把longs处理个8bit间隔
    // 16以内 2byte
    } else if (bitsPerValue <= 16) {
      nextPrimitive = 16; // 固定16bit -》2byte
      numLongs = BLOCK_SIZE / 4; // 要用32个long
      collapse16(longs);
    // 超过16bit（2byte） ：int32位
    } else {
      nextPrimitive = 32;// 固定间隔 32 bit -》int
      numLongs = BLOCK_SIZE / 2; // 64个long
      collapse32(longs);
    }
    // 把long数组已经转成固定间隔（8、16、32bit）的压缩数组
    // 固定间隔是作为遍历时使用
    // 固定间隔 = bitsPerValue(需求bit) + shift(用于组装的bit数)
    // n个shift = 1个bitsPerValue -》通过n个shift组装1个value

    // 1个间隔 分布：
    // 前bitsPerValue位：就是完整数据
    // 而剩下的shift：用于连同后面几个long的shift一起组装成1个bitsPerValue长度的value

    // 下面进行进一步压缩（shift的放入）
    final int numLongsPerShift = bitsPerValue * 2;// 2倍的入参固定bit数，即每次
    int idx = 0;
    // 当前固定bit - 入参固定bit
    // 距离目标的bit差距shift -》就是一个间隔里放完固定bit后剩下可用的bit数
    int shift = nextPrimitive - bitsPerValue;
    for (int i = 0; i < numLongsPerShift; ++i) {
      // 等于把每个间隔的value移到间隔的前bitsPerValue
      // 每个间隔预留的低shift位，准备存放shift数据
      tmp[i] = longs[idx++] << shift;
    }

    // 完成这里，等于把第1段连续long写入到存储long数组（每个都是写在存储的第1个value）

    // 就是只要当前固定间隔剩余bit数 还能继续放入1个value固定长度，就继续，直到不能放下1个完整value
    // 里面的循环就是放入对应段的完整value
    for (shift = shift - bitsPerValue; shift >= 0; shift -= bitsPerValue) {
      // 等于往结果long数组放入当前段的value
      for (int i = 0; i < numLongsPerShift; ++i) {
        tmp[i] |= longs[idx++] << shift;
      }
    }
    // 到这里，就是把第2段开始的能放完整value长度的数据放入存储long数组中

    // 剩下的就是写shift，用于组装成1个value，但存的时候分开
    // 剩下的long平均分配到这些shift上

    // （1个）存储long 当前还有多少bit未用
    final int remainingBitsPerLong = shift + bitsPerValue;// 等于还原回shift，因为上面把shift变成负数了
    final long maskRemainingBitsPerLong;
    // mask：用于就是保留每个间隔中shift数据，格式：间隔中低remainingBitsPerLong全是1
    if (nextPrimitive == 8) {
      maskRemainingBitsPerLong = MASKS8[remainingBitsPerLong];
    } else if (nextPrimitive == 16) {
      maskRemainingBitsPerLong = MASKS16[remainingBitsPerLong];
    } else {
      maskRemainingBitsPerLong = MASKS32[remainingBitsPerLong];
    }

    int tmpIdx = 0;
    //
    int remainingBitsPerValue = bitsPerValue;
    // 遍历剩下的long，不能用1个连续完整value存储的
    while (idx < numLongs) {
      if (remainingBitsPerValue >= remainingBitsPerLong) {
        // 直接放入完整1个value

        remainingBitsPerValue -= remainingBitsPerLong;
        //
        tmp[tmpIdx++] |= (longs[idx] >>> remainingBitsPerValue) & maskRemainingBitsPerLong;
        if (remainingBitsPerValue == 0) {
          idx++;
          remainingBitsPerValue = bitsPerValue;
        }
      } else {
        // 当前1个long剩余bit数 >= value固定bit数 -> 剩下还能放1个完整value

        final long mask1, mask2;
        if (nextPrimitive == 8) {
          mask1 = MASKS8[remainingBitsPerValue]; // 用于得到完整value
          mask2 = MASKS8[remainingBitsPerLong - remainingBitsPerValue]; // 用于保存shift
        } else if (nextPrimitive == 16) {
          mask1 = MASKS16[remainingBitsPerValue];
          mask2 = MASKS16[remainingBitsPerLong - remainingBitsPerValue];
        } else {
          mask1 = MASKS32[remainingBitsPerValue];
          mask2 = MASKS32[remainingBitsPerLong - remainingBitsPerValue];
        }
        // 加入1个完整value到对应long的段位置
        tmp[tmpIdx] |= (longs[idx++] & mask1) << (remainingBitsPerLong - remainingBitsPerValue);
        remainingBitsPerValue = bitsPerValue - remainingBitsPerLong + remainingBitsPerValue;

        tmp[tmpIdx++] |= (longs[idx] >>> remainingBitsPerValue) & mask2;
      }
    }

    // 写入存储long数组
    for (int i = 0; i < numLongsPerShift; ++i) {
      out.writeLong(tmp[i]);
    }
  }

  /** Number of bytes required to encode 128 integers of {@code bitsPerValue} bits per value. */
  int numBytes(int bitsPerValue) {
    return bitsPerValue << (BLOCK_SIZE_LOG2 - 3);
  }

  private static void decodeSlow(int bitsPerValue, DataInput in, long[] tmp, long[] longs)
      throws IOException {
    final int numLongs = bitsPerValue << 1;
    in.readLongs(tmp, 0, numLongs);
    final long mask = MASKS32[bitsPerValue];
    int longsIdx = 0;
    int shift = 32 - bitsPerValue;
    for (; shift >= 0; shift -= bitsPerValue) {
      shiftLongs(tmp, numLongs, longs, longsIdx, shift, mask);
      longsIdx += numLongs;
    }
    final int remainingBitsPerLong = shift + bitsPerValue;
    final long mask32RemainingBitsPerLong = MASKS32[remainingBitsPerLong];
    int tmpIdx = 0;
    int remainingBits = remainingBitsPerLong;
    for (; longsIdx < BLOCK_SIZE / 2; ++longsIdx) {
      int b = bitsPerValue - remainingBits;
      long l = (tmp[tmpIdx++] & MASKS32[remainingBits]) << b;
      while (b >= remainingBitsPerLong) {
        b -= remainingBitsPerLong;
        l |= (tmp[tmpIdx++] & mask32RemainingBitsPerLong) << b;
      }
      if (b > 0) {
        l |= (tmp[tmpIdx] >>> (remainingBitsPerLong - b)) & MASKS32[b];
        remainingBits = remainingBitsPerLong - b;
      } else {
        remainingBits = remainingBitsPerLong;
      }
      longs[longsIdx] = l;
    }
  }

  /**
   * The pattern that this shiftLongs method applies is recognized by the C2 compiler, which
   * generates SIMD instructions for it in order to shift multiple longs at once.
   */
  private static void shiftLongs(long[] a, int count, long[] b, int bi, int shift, long mask) {
    // 从bi开始放入count个
    for (int i = 0; i < count; ++i) {
      //
      b[bi + i] = (a[i] >>> shift) & mask;
    }
  }

  private static final long[] MASKS8 = new long[8];
  private static final long[] MASKS16 = new long[16];
  private static final long[] MASKS32 = new long[32];

  static {
    // 初始化MASK
    for (int i = 0; i < 8; ++i) {
      // 1:8个0000 0001
      // 2:8个0000 0010
      MASKS8[i] = mask8(i);
    }
    for (int i = 0; i < 16; ++i) {
      // 1: 4个0000 0000 0000 0001
      MASKS16[i] = mask16(i);
    }
    for (int i = 0; i < 32; ++i) {
      // 1: 2个0000 0000 0000 0000 0000 0000 0000 0001
      MASKS32[i] = mask32(i);
    }
  }
  // mark values in array as final longs to avoid the cost of reading array, arrays should only be
  // used when the idx is a variable
  private static final long MASK8_1 = MASKS8[1];
  private static final long MASK8_2 = MASKS8[2];
  private static final long MASK8_3 = MASKS8[3];
  private static final long MASK8_4 = MASKS8[4];
  private static final long MASK8_5 = MASKS8[5];
  private static final long MASK8_6 = MASKS8[6];
  private static final long MASK8_7 = MASKS8[7];
  private static final long MASK16_1 = MASKS16[1];
  private static final long MASK16_2 = MASKS16[2];
  private static final long MASK16_3 = MASKS16[3];
  private static final long MASK16_4 = MASKS16[4];
  private static final long MASK16_5 = MASKS16[5];
  private static final long MASK16_6 = MASKS16[6];
  private static final long MASK16_7 = MASKS16[7];
  private static final long MASK16_9 = MASKS16[9];
  private static final long MASK16_10 = MASKS16[10];
  private static final long MASK16_11 = MASKS16[11];
  private static final long MASK16_12 = MASKS16[12];
  private static final long MASK16_13 = MASKS16[13];
  private static final long MASK16_14 = MASKS16[14];
  private static final long MASK16_15 = MASKS16[15];
  private static final long MASK32_1 = MASKS32[1];
  private static final long MASK32_2 = MASKS32[2];
  private static final long MASK32_3 = MASKS32[3];
  private static final long MASK32_4 = MASKS32[4];
  private static final long MASK32_5 = MASKS32[5];
  private static final long MASK32_6 = MASKS32[6];
  private static final long MASK32_7 = MASKS32[7];
  private static final long MASK32_8 = MASKS32[8];
  private static final long MASK32_9 = MASKS32[9];
  private static final long MASK32_10 = MASKS32[10];
  private static final long MASK32_11 = MASKS32[11];
  private static final long MASK32_12 = MASKS32[12];
  private static final long MASK32_13 = MASKS32[13];
  private static final long MASK32_14 = MASKS32[14];
  private static final long MASK32_15 = MASKS32[15];
  private static final long MASK32_17 = MASKS32[17];
  private static final long MASK32_18 = MASKS32[18];
  private static final long MASK32_19 = MASKS32[19];
  private static final long MASK32_20 = MASKS32[20];
  private static final long MASK32_21 = MASKS32[21];
  private static final long MASK32_22 = MASKS32[22];
  private static final long MASK32_23 = MASKS32[23];
  private static final long MASK32_24 = MASKS32[24];

  /** Decode 128 integers into {@code longs}. */
  void decode(int bitsPerValue, DataInput in, long[] longs) throws IOException {
    switch (bitsPerValue) {
      case 1:
        decode1(in, tmp, longs);
        expand8(longs);
        break;
      case 2:
        decode2(in, tmp, longs);
        expand8(longs);
        break;
      case 3:
        decode3(in, tmp, longs);
        expand8(longs);
        break;
      case 4:
        decode4(in, tmp, longs);
        expand8(longs);
        break;
      case 5:
        decode5(in, tmp, longs);
        expand8(longs);
        break;
        // 固定bit数>shift
      case 6:
        decode6(in, tmp, longs);
        expand8(longs);
        break;
      case 7:
        decode7(in, tmp, longs);
        expand8(longs);
        break;
      case 8:
        decode8(in, tmp, longs);
        expand8(longs);
        break;
      case 9:
        decode9(in, tmp, longs);
        expand16(longs);
        break;
      case 10:
        decode10(in, tmp, longs);
        expand16(longs);
        break;
      case 11:
        decode11(in, tmp, longs);
        expand16(longs);
        break;
      case 12:
        decode12(in, tmp, longs);
        expand16(longs);
        break;
      case 13:
        decode13(in, tmp, longs);
        expand16(longs);
        break;
      case 14:
        decode14(in, tmp, longs);
        expand16(longs);
        break;
      case 15:
        decode15(in, tmp, longs);
        expand16(longs);
        break;
      case 16:
        decode16(in, tmp, longs);
        expand16(longs);
        break;
      case 17:
        decode17(in, tmp, longs);
        expand32(longs);
        break;
      case 18:
        decode18(in, tmp, longs);
        expand32(longs);
        break;
      case 19:
        decode19(in, tmp, longs);
        expand32(longs);
        break;
      case 20:
        decode20(in, tmp, longs);
        expand32(longs);
        break;
      case 21:
        decode21(in, tmp, longs);
        expand32(longs);
        break;
      case 22:
        decode22(in, tmp, longs);
        expand32(longs);
        break;
      case 23:
        decode23(in, tmp, longs);
        expand32(longs);
        break;
      case 24:
        decode24(in, tmp, longs);
        expand32(longs);
        break;
      default:
        decodeSlow(bitsPerValue, in, tmp, longs);
        expand32(longs);
        break;
    }
  }

  /**
   * Decodes 128 integers into 64 {@code longs} such that each long contains two values, each
   * represented with 32 bits. Values [0..63] are encoded in the high-order bits of {@code longs}
   * [0..63], and values [64..127] are encoded in the low-order bits of {@code longs} [0..63]. This
   * representation may allow subsequent operations to be performed on two values at a time.
   */
  void decodeTo32(int bitsPerValue, DataInput in, long[] longs) throws IOException {
    switch (bitsPerValue) {
      case 1:
        decode1(in, tmp, longs);
        expand8To32(longs);
        break;
      case 2:
        decode2(in, tmp, longs);
        expand8To32(longs);
        break;
      case 3:
        decode3(in, tmp, longs);
        expand8To32(longs);
        break;
      case 4:
        decode4(in, tmp, longs);
        expand8To32(longs);
        break;
      case 5:
        decode5(in, tmp, longs);
        expand8To32(longs);
        break;
      case 6:
        decode6(in, tmp, longs);
        expand8To32(longs);
        break;
      case 7:
        decode7(in, tmp, longs);
        expand8To32(longs);
        break;
      case 8:
        decode8(in, tmp, longs);
        expand8To32(longs);
        break;
      case 9:
        decode9(in, tmp, longs);
        expand16To32(longs);
        break;
      case 10:
        decode10(in, tmp, longs);
        expand16To32(longs);
        break;
      case 11:
        decode11(in, tmp, longs);
        expand16To32(longs);
        break;
      case 12:
        decode12(in, tmp, longs);
        expand16To32(longs);
        break;
      case 13:
        decode13(in, tmp, longs);
        expand16To32(longs);
        break;
      case 14:
        decode14(in, tmp, longs);
        expand16To32(longs);
        break;
      case 15:
        decode15(in, tmp, longs);
        expand16To32(longs);
        break;
      case 16:
        decode16(in, tmp, longs);
        expand16To32(longs);
        break;
      case 17:
        decode17(in, tmp, longs);
        break;
      case 18:
        decode18(in, tmp, longs);
        break;
      case 19:
        decode19(in, tmp, longs);
        break;
      case 20:
        decode20(in, tmp, longs);
        break;
      case 21:
        decode21(in, tmp, longs);
        break;
      case 22:
        decode22(in, tmp, longs);
        break;
      case 23:
        decode23(in, tmp, longs);
        break;
      case 24:
        decode24(in, tmp, longs);
        break;
      default:
        decodeSlow(bitsPerValue, in, tmp, longs);
        break;
    }
  }

  private static void decode1(DataInput in, long[] tmp, long[] longs) throws IOException {
    in.readLongs(tmp, 0, 2);
    shiftLongs(tmp, 2, longs, 0, 7, MASK8_1);
    shiftLongs(tmp, 2, longs, 2, 6, MASK8_1);
    shiftLongs(tmp, 2, longs, 4, 5, MASK8_1);
    shiftLongs(tmp, 2, longs, 6, 4, MASK8_1);
    shiftLongs(tmp, 2, longs, 8, 3, MASK8_1);
    shiftLongs(tmp, 2, longs, 10, 2, MASK8_1);
    shiftLongs(tmp, 2, longs, 12, 1, MASK8_1);
    shiftLongs(tmp, 2, longs, 14, 0, MASK8_1);
  }

  private static void decode2(DataInput in, long[] tmp, long[] longs) throws IOException {
    in.readLongs(tmp, 0, 4);
    shiftLongs(tmp, 4, longs, 0, 6, MASK8_2);
    shiftLongs(tmp, 4, longs, 4, 4, MASK8_2);
    shiftLongs(tmp, 4, longs, 8, 2, MASK8_2);
    shiftLongs(tmp, 4, longs, 12, 0, MASK8_2);
  }

  private static void decode3(DataInput in, long[] tmp, long[] longs) throws IOException {
    // 1个间隔格式：XXXYYYZZ （X：前6个long的3位，Y：第2段6个long的3位，ZZ：剩余的shift）

    in.readLongs(tmp, 0, 6);
    // 右移5位再&mask ，得到每个间隔的准确value（00000XXX）,放到结果数组前6个
    // 前1到6个，使用间隔前3位存完整value
    shiftLongs(tmp, 6, longs, 0, 5, MASK8_3);
    // 右移2位再&mask_3 : n个 00000YYY , 放到结果数组的7到12个中
    // 前7到12，使用间隔中间存完整value，格式：XXXYYY00
    shiftLongs(tmp, 6, longs, 6, 2, MASK8_3);

    // 那么下面就是把shift组装
    for (int iter = 0, tmpIdx = 0, longsIdx = 12; iter < 2; ++iter, tmpIdx += 3, longsIdx += 2) {
      // 保留第1项的shift（共2位），然后左移1位（XX0），等待or后面的
      long l0 = (tmp[tmpIdx + 0] & MASK8_2) << 1;
      // 第2项的shift（第1位）加到上面的已有2位上，得到3位
      l0 |= (tmp[tmpIdx + 1] >>> 1) & MASK8_1;
      longs[longsIdx + 0] = l0;

      // 剩下那1bit跟下一个long的shift（2bit）合并
      long l1 = (tmp[tmpIdx + 1] & MASK8_1) << 2;
      l1 |= (tmp[tmpIdx + 2] & MASK8_2) << 0;
      longs[longsIdx + 1] = l1;
    }

    //所以存储文件（long）实际布局规律
    // 1个long：n个固定大小 + shift -》例如这里3， XXX（固定）YYY（固定）ZZ（shift）
    // 跟其他结构一样，long第一个value就是第1段，第二个是第二段
    // 转换时先把所有第1段转换（这个6个long第1个value即结果第1段1到6的），再去转换第二段（6个第2个value即结果第2段7到2），然后再去转换所有shift（剩下的是13后）
  }

  private static void decode4(DataInput in, long[] tmp, long[] longs) throws IOException {
    in.readLongs(tmp, 0, 8);
    shiftLongs(tmp, 8, longs, 0, 4, MASK8_4);
    shiftLongs(tmp, 8, longs, 8, 0, MASK8_4);
  }

  private static void decode5(DataInput in, long[] tmp, long[] longs) throws IOException {
    in.readLongs(tmp, 0, 10);
    shiftLongs(tmp, 10, longs, 0, 3, MASK8_5);
    for (int iter = 0, tmpIdx = 0, longsIdx = 10; iter < 2; ++iter, tmpIdx += 5, longsIdx += 3) {
      long l0 = (tmp[tmpIdx + 0] & MASK8_3) << 2;
      l0 |= (tmp[tmpIdx + 1] >>> 1) & MASK8_2;
      longs[longsIdx + 0] = l0;
      long l1 = (tmp[tmpIdx + 1] & MASK8_1) << 4;
      l1 |= (tmp[tmpIdx + 2] & MASK8_3) << 1;
      l1 |= (tmp[tmpIdx + 3] >>> 2) & MASK8_1;
      longs[longsIdx + 1] = l1;
      long l2 = (tmp[tmpIdx + 3] & MASK8_2) << 3;
      l2 |= (tmp[tmpIdx + 4] & MASK8_3) << 0;
      longs[longsIdx + 2] = l2;
    }
  }

  private static void decode6(DataInput in, long[] tmp, long[] longs) throws IOException {
    // 1个long原始格式：8个XXXXXXYY （X代表用于开始2*bitPerValue转换，Y代表后面组装转换的）

    // 前面这是第1到12个long转换
    // 先读取2*bitPerValue个long
    in.readLongs(tmp, 0, 12);
    // 放入前12个（2*bitPerValue个long）到longs（结果数组）
    // 右移shift（2位）：00XXXXXX YYXX...XXXX -》就是把每8个前6个放到正常位置（低2位准备丢弃）
    // & mask8_6(8个00111111): 8个00xxxxxx（YY2位都被置为0）-》低2位丢弃
    // 这样得到long是8个00XXXXXX（每个8bit的前6位都放到正常位置）
    shiftLongs(tmp, 12, longs, 0, 2, MASK8_6);


    // 下面是转换出来的13到16个long（组装）

    // tmp 的前12个都更新
    // tmp的& mask8_2（8个00000011）8个000000xx(保留低2位)
    shiftLongs(tmp, 12, tmp, 0, 0, MASK8_2);
    // longsIdx从第13个开始，每个+1
    // 每次都会tmpIdx+3，即每3个前面的long用于后面的1个
    // 剩下4个long(13-16)，基于前面的每3个long，例如13基于（1、2、3），16基于（10、11、12）
    for (int iter = 0, tmpIdx = 0, longsIdx = 12; iter < 4; ++iter, tmpIdx += 3, longsIdx += 1) {
      long l0 = tmp[tmpIdx + 0] << 4;// 左移4位-》00xx 000..00XX0000 (固定头2位0)
      l0 |= tmp[tmpIdx + 1] << 2;// 后一个long 左移2位-》0000XX00..00XX00（固定头4位0）
      l0 |= tmp[tmpIdx + 2] << 0;// or上后2个long -> 000000xx..000000xx（固定头6位0）

      // OR的结果就是：00XXYYZZ00XXYYZZ..00XXYYZZ (x、y、z分别上面顺数的3个long的元素)
      // 所以组成了8个00XXYYZZ(刚好6个使用的，符合bitPerValue)

      // 最后or的结果作为最终long
      longs[longsIdx + 0] = l0;
    }
  }

  private static void decode7(DataInput in, long[] tmp, long[] longs) throws IOException {
    in.readLongs(tmp, 0, 14);
    shiftLongs(tmp, 14, longs, 0, 1, MASK8_7);
    shiftLongs(tmp, 14, tmp, 0, 0, MASK8_1);
    for (int iter = 0, tmpIdx = 0, longsIdx = 14; iter < 2; ++iter, tmpIdx += 7, longsIdx += 1) {
      long l0 = tmp[tmpIdx + 0] << 6;
      l0 |= tmp[tmpIdx + 1] << 5;
      l0 |= tmp[tmpIdx + 2] << 4;
      l0 |= tmp[tmpIdx + 3] << 3;
      l0 |= tmp[tmpIdx + 4] << 2;
      l0 |= tmp[tmpIdx + 5] << 1;
      l0 |= tmp[tmpIdx + 6] << 0;
      longs[longsIdx + 0] = l0;
    }
  }

  private static void decode8(DataInput in, long[] tmp, long[] longs) throws IOException {
    in.readLongs(longs, 0, 16);
  }

  private static void decode9(DataInput in, long[] tmp, long[] longs) throws IOException {
    in.readLongs(tmp, 0, 18);
    shiftLongs(tmp, 18, longs, 0, 7, MASK16_9);
    for (int iter = 0, tmpIdx = 0, longsIdx = 18; iter < 2; ++iter, tmpIdx += 9, longsIdx += 7) {
      long l0 = (tmp[tmpIdx + 0] & MASK16_7) << 2;
      l0 |= (tmp[tmpIdx + 1] >>> 5) & MASK16_2;
      longs[longsIdx + 0] = l0;
      long l1 = (tmp[tmpIdx + 1] & MASK16_5) << 4;
      l1 |= (tmp[tmpIdx + 2] >>> 3) & MASK16_4;
      longs[longsIdx + 1] = l1;
      long l2 = (tmp[tmpIdx + 2] & MASK16_3) << 6;
      l2 |= (tmp[tmpIdx + 3] >>> 1) & MASK16_6;
      longs[longsIdx + 2] = l2;
      long l3 = (tmp[tmpIdx + 3] & MASK16_1) << 8;
      l3 |= (tmp[tmpIdx + 4] & MASK16_7) << 1;
      l3 |= (tmp[tmpIdx + 5] >>> 6) & MASK16_1;
      longs[longsIdx + 3] = l3;
      long l4 = (tmp[tmpIdx + 5] & MASK16_6) << 3;
      l4 |= (tmp[tmpIdx + 6] >>> 4) & MASK16_3;
      longs[longsIdx + 4] = l4;
      long l5 = (tmp[tmpIdx + 6] & MASK16_4) << 5;
      l5 |= (tmp[tmpIdx + 7] >>> 2) & MASK16_5;
      longs[longsIdx + 5] = l5;
      long l6 = (tmp[tmpIdx + 7] & MASK16_2) << 7;
      l6 |= (tmp[tmpIdx + 8] & MASK16_7) << 0;
      longs[longsIdx + 6] = l6;
    }
  }

  private static void decode10(DataInput in, long[] tmp, long[] longs) throws IOException {
    in.readLongs(tmp, 0, 20);
    shiftLongs(tmp, 20, longs, 0, 6, MASK16_10);
    for (int iter = 0, tmpIdx = 0, longsIdx = 20; iter < 4; ++iter, tmpIdx += 5, longsIdx += 3) {
      long l0 = (tmp[tmpIdx + 0] & MASK16_6) << 4;
      l0 |= (tmp[tmpIdx + 1] >>> 2) & MASK16_4;
      longs[longsIdx + 0] = l0;
      long l1 = (tmp[tmpIdx + 1] & MASK16_2) << 8;
      l1 |= (tmp[tmpIdx + 2] & MASK16_6) << 2;
      l1 |= (tmp[tmpIdx + 3] >>> 4) & MASK16_2;
      longs[longsIdx + 1] = l1;
      long l2 = (tmp[tmpIdx + 3] & MASK16_4) << 6;
      l2 |= (tmp[tmpIdx + 4] & MASK16_6) << 0;
      longs[longsIdx + 2] = l2;
    }
  }

  private static void decode11(DataInput in, long[] tmp, long[] longs) throws IOException {
    in.readLongs(tmp, 0, 22);
    shiftLongs(tmp, 22, longs, 0, 5, MASK16_11);
    for (int iter = 0, tmpIdx = 0, longsIdx = 22; iter < 2; ++iter, tmpIdx += 11, longsIdx += 5) {
      long l0 = (tmp[tmpIdx + 0] & MASK16_5) << 6;
      l0 |= (tmp[tmpIdx + 1] & MASK16_5) << 1;
      l0 |= (tmp[tmpIdx + 2] >>> 4) & MASK16_1;
      longs[longsIdx + 0] = l0;
      long l1 = (tmp[tmpIdx + 2] & MASK16_4) << 7;
      l1 |= (tmp[tmpIdx + 3] & MASK16_5) << 2;
      l1 |= (tmp[tmpIdx + 4] >>> 3) & MASK16_2;
      longs[longsIdx + 1] = l1;
      long l2 = (tmp[tmpIdx + 4] & MASK16_3) << 8;
      l2 |= (tmp[tmpIdx + 5] & MASK16_5) << 3;
      l2 |= (tmp[tmpIdx + 6] >>> 2) & MASK16_3;
      longs[longsIdx + 2] = l2;
      long l3 = (tmp[tmpIdx + 6] & MASK16_2) << 9;
      l3 |= (tmp[tmpIdx + 7] & MASK16_5) << 4;
      l3 |= (tmp[tmpIdx + 8] >>> 1) & MASK16_4;
      longs[longsIdx + 3] = l3;
      long l4 = (tmp[tmpIdx + 8] & MASK16_1) << 10;
      l4 |= (tmp[tmpIdx + 9] & MASK16_5) << 5;
      l4 |= (tmp[tmpIdx + 10] & MASK16_5) << 0;
      longs[longsIdx + 4] = l4;
    }
  }

  private static void decode12(DataInput in, long[] tmp, long[] longs) throws IOException {
    in.readLongs(tmp, 0, 24);
    shiftLongs(tmp, 24, longs, 0, 4, MASK16_12);
    shiftLongs(tmp, 24, tmp, 0, 0, MASK16_4);
    for (int iter = 0, tmpIdx = 0, longsIdx = 24; iter < 8; ++iter, tmpIdx += 3, longsIdx += 1) {
      long l0 = tmp[tmpIdx + 0] << 8;
      l0 |= tmp[tmpIdx + 1] << 4;
      l0 |= tmp[tmpIdx + 2] << 0;
      longs[longsIdx + 0] = l0;
    }
  }

  private static void decode13(DataInput in, long[] tmp, long[] longs) throws IOException {
    in.readLongs(tmp, 0, 26);
    shiftLongs(tmp, 26, longs, 0, 3, MASK16_13);
    for (int iter = 0, tmpIdx = 0, longsIdx = 26; iter < 2; ++iter, tmpIdx += 13, longsIdx += 3) {
      long l0 = (tmp[tmpIdx + 0] & MASK16_3) << 10;
      l0 |= (tmp[tmpIdx + 1] & MASK16_3) << 7;
      l0 |= (tmp[tmpIdx + 2] & MASK16_3) << 4;
      l0 |= (tmp[tmpIdx + 3] & MASK16_3) << 1;
      l0 |= (tmp[tmpIdx + 4] >>> 2) & MASK16_1;
      longs[longsIdx + 0] = l0;
      long l1 = (tmp[tmpIdx + 4] & MASK16_2) << 11;
      l1 |= (tmp[tmpIdx + 5] & MASK16_3) << 8;
      l1 |= (tmp[tmpIdx + 6] & MASK16_3) << 5;
      l1 |= (tmp[tmpIdx + 7] & MASK16_3) << 2;
      l1 |= (tmp[tmpIdx + 8] >>> 1) & MASK16_2;
      longs[longsIdx + 1] = l1;
      long l2 = (tmp[tmpIdx + 8] & MASK16_1) << 12;
      l2 |= (tmp[tmpIdx + 9] & MASK16_3) << 9;
      l2 |= (tmp[tmpIdx + 10] & MASK16_3) << 6;
      l2 |= (tmp[tmpIdx + 11] & MASK16_3) << 3;
      l2 |= (tmp[tmpIdx + 12] & MASK16_3) << 0;
      longs[longsIdx + 2] = l2;
    }
  }

  private static void decode14(DataInput in, long[] tmp, long[] longs) throws IOException {
    in.readLongs(tmp, 0, 28);
    shiftLongs(tmp, 28, longs, 0, 2, MASK16_14);
    shiftLongs(tmp, 28, tmp, 0, 0, MASK16_2);
    for (int iter = 0, tmpIdx = 0, longsIdx = 28; iter < 4; ++iter, tmpIdx += 7, longsIdx += 1) {
      long l0 = tmp[tmpIdx + 0] << 12;
      l0 |= tmp[tmpIdx + 1] << 10;
      l0 |= tmp[tmpIdx + 2] << 8;
      l0 |= tmp[tmpIdx + 3] << 6;
      l0 |= tmp[tmpIdx + 4] << 4;
      l0 |= tmp[tmpIdx + 5] << 2;
      l0 |= tmp[tmpIdx + 6] << 0;
      longs[longsIdx + 0] = l0;
    }
  }

  private static void decode15(DataInput in, long[] tmp, long[] longs) throws IOException {
    in.readLongs(tmp, 0, 30);
    shiftLongs(tmp, 30, longs, 0, 1, MASK16_15);
    shiftLongs(tmp, 30, tmp, 0, 0, MASK16_1);
    for (int iter = 0, tmpIdx = 0, longsIdx = 30; iter < 2; ++iter, tmpIdx += 15, longsIdx += 1) {
      long l0 = tmp[tmpIdx + 0] << 14;
      l0 |= tmp[tmpIdx + 1] << 13;
      l0 |= tmp[tmpIdx + 2] << 12;
      l0 |= tmp[tmpIdx + 3] << 11;
      l0 |= tmp[tmpIdx + 4] << 10;
      l0 |= tmp[tmpIdx + 5] << 9;
      l0 |= tmp[tmpIdx + 6] << 8;
      l0 |= tmp[tmpIdx + 7] << 7;
      l0 |= tmp[tmpIdx + 8] << 6;
      l0 |= tmp[tmpIdx + 9] << 5;
      l0 |= tmp[tmpIdx + 10] << 4;
      l0 |= tmp[tmpIdx + 11] << 3;
      l0 |= tmp[tmpIdx + 12] << 2;
      l0 |= tmp[tmpIdx + 13] << 1;
      l0 |= tmp[tmpIdx + 14] << 0;
      longs[longsIdx + 0] = l0;
    }
  }

  private static void decode16(DataInput in, long[] tmp, long[] longs) throws IOException {
    in.readLongs(longs, 0, 32);
  }

  private static void decode17(DataInput in, long[] tmp, long[] longs) throws IOException {
    in.readLongs(tmp, 0, 34);
    shiftLongs(tmp, 34, longs, 0, 15, MASK32_17);
    for (int iter = 0, tmpIdx = 0, longsIdx = 34; iter < 2; ++iter, tmpIdx += 17, longsIdx += 15) {
      long l0 = (tmp[tmpIdx + 0] & MASK32_15) << 2;
      l0 |= (tmp[tmpIdx + 1] >>> 13) & MASK32_2;
      longs[longsIdx + 0] = l0;
      long l1 = (tmp[tmpIdx + 1] & MASK32_13) << 4;
      l1 |= (tmp[tmpIdx + 2] >>> 11) & MASK32_4;
      longs[longsIdx + 1] = l1;
      long l2 = (tmp[tmpIdx + 2] & MASK32_11) << 6;
      l2 |= (tmp[tmpIdx + 3] >>> 9) & MASK32_6;
      longs[longsIdx + 2] = l2;
      long l3 = (tmp[tmpIdx + 3] & MASK32_9) << 8;
      l3 |= (tmp[tmpIdx + 4] >>> 7) & MASK32_8;
      longs[longsIdx + 3] = l3;
      long l4 = (tmp[tmpIdx + 4] & MASK32_7) << 10;
      l4 |= (tmp[tmpIdx + 5] >>> 5) & MASK32_10;
      longs[longsIdx + 4] = l4;
      long l5 = (tmp[tmpIdx + 5] & MASK32_5) << 12;
      l5 |= (tmp[tmpIdx + 6] >>> 3) & MASK32_12;
      longs[longsIdx + 5] = l5;
      long l6 = (tmp[tmpIdx + 6] & MASK32_3) << 14;
      l6 |= (tmp[tmpIdx + 7] >>> 1) & MASK32_14;
      longs[longsIdx + 6] = l6;
      long l7 = (tmp[tmpIdx + 7] & MASK32_1) << 16;
      l7 |= (tmp[tmpIdx + 8] & MASK32_15) << 1;
      l7 |= (tmp[tmpIdx + 9] >>> 14) & MASK32_1;
      longs[longsIdx + 7] = l7;
      long l8 = (tmp[tmpIdx + 9] & MASK32_14) << 3;
      l8 |= (tmp[tmpIdx + 10] >>> 12) & MASK32_3;
      longs[longsIdx + 8] = l8;
      long l9 = (tmp[tmpIdx + 10] & MASK32_12) << 5;
      l9 |= (tmp[tmpIdx + 11] >>> 10) & MASK32_5;
      longs[longsIdx + 9] = l9;
      long l10 = (tmp[tmpIdx + 11] & MASK32_10) << 7;
      l10 |= (tmp[tmpIdx + 12] >>> 8) & MASK32_7;
      longs[longsIdx + 10] = l10;
      long l11 = (tmp[tmpIdx + 12] & MASK32_8) << 9;
      l11 |= (tmp[tmpIdx + 13] >>> 6) & MASK32_9;
      longs[longsIdx + 11] = l11;
      long l12 = (tmp[tmpIdx + 13] & MASK32_6) << 11;
      l12 |= (tmp[tmpIdx + 14] >>> 4) & MASK32_11;
      longs[longsIdx + 12] = l12;
      long l13 = (tmp[tmpIdx + 14] & MASK32_4) << 13;
      l13 |= (tmp[tmpIdx + 15] >>> 2) & MASK32_13;
      longs[longsIdx + 13] = l13;
      long l14 = (tmp[tmpIdx + 15] & MASK32_2) << 15;
      l14 |= (tmp[tmpIdx + 16] & MASK32_15) << 0;
      longs[longsIdx + 14] = l14;
    }
  }

  private static void decode18(DataInput in, long[] tmp, long[] longs) throws IOException {
    in.readLongs(tmp, 0, 36);
    shiftLongs(tmp, 36, longs, 0, 14, MASK32_18);
    for (int iter = 0, tmpIdx = 0, longsIdx = 36; iter < 4; ++iter, tmpIdx += 9, longsIdx += 7) {
      long l0 = (tmp[tmpIdx + 0] & MASK32_14) << 4;
      l0 |= (tmp[tmpIdx + 1] >>> 10) & MASK32_4;
      longs[longsIdx + 0] = l0;
      long l1 = (tmp[tmpIdx + 1] & MASK32_10) << 8;
      l1 |= (tmp[tmpIdx + 2] >>> 6) & MASK32_8;
      longs[longsIdx + 1] = l1;
      long l2 = (tmp[tmpIdx + 2] & MASK32_6) << 12;
      l2 |= (tmp[tmpIdx + 3] >>> 2) & MASK32_12;
      longs[longsIdx + 2] = l2;
      long l3 = (tmp[tmpIdx + 3] & MASK32_2) << 16;
      l3 |= (tmp[tmpIdx + 4] & MASK32_14) << 2;
      l3 |= (tmp[tmpIdx + 5] >>> 12) & MASK32_2;
      longs[longsIdx + 3] = l3;
      long l4 = (tmp[tmpIdx + 5] & MASK32_12) << 6;
      l4 |= (tmp[tmpIdx + 6] >>> 8) & MASK32_6;
      longs[longsIdx + 4] = l4;
      long l5 = (tmp[tmpIdx + 6] & MASK32_8) << 10;
      l5 |= (tmp[tmpIdx + 7] >>> 4) & MASK32_10;
      longs[longsIdx + 5] = l5;
      long l6 = (tmp[tmpIdx + 7] & MASK32_4) << 14;
      l6 |= (tmp[tmpIdx + 8] & MASK32_14) << 0;
      longs[longsIdx + 6] = l6;
    }
  }

  private static void decode19(DataInput in, long[] tmp, long[] longs) throws IOException {
    in.readLongs(tmp, 0, 38);
    shiftLongs(tmp, 38, longs, 0, 13, MASK32_19);
    for (int iter = 0, tmpIdx = 0, longsIdx = 38; iter < 2; ++iter, tmpIdx += 19, longsIdx += 13) {
      long l0 = (tmp[tmpIdx + 0] & MASK32_13) << 6;
      l0 |= (tmp[tmpIdx + 1] >>> 7) & MASK32_6;
      longs[longsIdx + 0] = l0;
      long l1 = (tmp[tmpIdx + 1] & MASK32_7) << 12;
      l1 |= (tmp[tmpIdx + 2] >>> 1) & MASK32_12;
      longs[longsIdx + 1] = l1;
      long l2 = (tmp[tmpIdx + 2] & MASK32_1) << 18;
      l2 |= (tmp[tmpIdx + 3] & MASK32_13) << 5;
      l2 |= (tmp[tmpIdx + 4] >>> 8) & MASK32_5;
      longs[longsIdx + 2] = l2;
      long l3 = (tmp[tmpIdx + 4] & MASK32_8) << 11;
      l3 |= (tmp[tmpIdx + 5] >>> 2) & MASK32_11;
      longs[longsIdx + 3] = l3;
      long l4 = (tmp[tmpIdx + 5] & MASK32_2) << 17;
      l4 |= (tmp[tmpIdx + 6] & MASK32_13) << 4;
      l4 |= (tmp[tmpIdx + 7] >>> 9) & MASK32_4;
      longs[longsIdx + 4] = l4;
      long l5 = (tmp[tmpIdx + 7] & MASK32_9) << 10;
      l5 |= (tmp[tmpIdx + 8] >>> 3) & MASK32_10;
      longs[longsIdx + 5] = l5;
      long l6 = (tmp[tmpIdx + 8] & MASK32_3) << 16;
      l6 |= (tmp[tmpIdx + 9] & MASK32_13) << 3;
      l6 |= (tmp[tmpIdx + 10] >>> 10) & MASK32_3;
      longs[longsIdx + 6] = l6;
      long l7 = (tmp[tmpIdx + 10] & MASK32_10) << 9;
      l7 |= (tmp[tmpIdx + 11] >>> 4) & MASK32_9;
      longs[longsIdx + 7] = l7;
      long l8 = (tmp[tmpIdx + 11] & MASK32_4) << 15;
      l8 |= (tmp[tmpIdx + 12] & MASK32_13) << 2;
      l8 |= (tmp[tmpIdx + 13] >>> 11) & MASK32_2;
      longs[longsIdx + 8] = l8;
      long l9 = (tmp[tmpIdx + 13] & MASK32_11) << 8;
      l9 |= (tmp[tmpIdx + 14] >>> 5) & MASK32_8;
      longs[longsIdx + 9] = l9;
      long l10 = (tmp[tmpIdx + 14] & MASK32_5) << 14;
      l10 |= (tmp[tmpIdx + 15] & MASK32_13) << 1;
      l10 |= (tmp[tmpIdx + 16] >>> 12) & MASK32_1;
      longs[longsIdx + 10] = l10;
      long l11 = (tmp[tmpIdx + 16] & MASK32_12) << 7;
      l11 |= (tmp[tmpIdx + 17] >>> 6) & MASK32_7;
      longs[longsIdx + 11] = l11;
      long l12 = (tmp[tmpIdx + 17] & MASK32_6) << 13;
      l12 |= (tmp[tmpIdx + 18] & MASK32_13) << 0;
      longs[longsIdx + 12] = l12;
    }
  }

  private static void decode20(DataInput in, long[] tmp, long[] longs) throws IOException {
    in.readLongs(tmp, 0, 40);
    shiftLongs(tmp, 40, longs, 0, 12, MASK32_20);
    for (int iter = 0, tmpIdx = 0, longsIdx = 40; iter < 8; ++iter, tmpIdx += 5, longsIdx += 3) {
      long l0 = (tmp[tmpIdx + 0] & MASK32_12) << 8;
      l0 |= (tmp[tmpIdx + 1] >>> 4) & MASK32_8;
      longs[longsIdx + 0] = l0;
      long l1 = (tmp[tmpIdx + 1] & MASK32_4) << 16;
      l1 |= (tmp[tmpIdx + 2] & MASK32_12) << 4;
      l1 |= (tmp[tmpIdx + 3] >>> 8) & MASK32_4;
      longs[longsIdx + 1] = l1;
      long l2 = (tmp[tmpIdx + 3] & MASK32_8) << 12;
      l2 |= (tmp[tmpIdx + 4] & MASK32_12) << 0;
      longs[longsIdx + 2] = l2;
    }
  }

  private static void decode21(DataInput in, long[] tmp, long[] longs) throws IOException {
    in.readLongs(tmp, 0, 42);
    shiftLongs(tmp, 42, longs, 0, 11, MASK32_21);
    for (int iter = 0, tmpIdx = 0, longsIdx = 42; iter < 2; ++iter, tmpIdx += 21, longsIdx += 11) {
      long l0 = (tmp[tmpIdx + 0] & MASK32_11) << 10;
      l0 |= (tmp[tmpIdx + 1] >>> 1) & MASK32_10;
      longs[longsIdx + 0] = l0;
      long l1 = (tmp[tmpIdx + 1] & MASK32_1) << 20;
      l1 |= (tmp[tmpIdx + 2] & MASK32_11) << 9;
      l1 |= (tmp[tmpIdx + 3] >>> 2) & MASK32_9;
      longs[longsIdx + 1] = l1;
      long l2 = (tmp[tmpIdx + 3] & MASK32_2) << 19;
      l2 |= (tmp[tmpIdx + 4] & MASK32_11) << 8;
      l2 |= (tmp[tmpIdx + 5] >>> 3) & MASK32_8;
      longs[longsIdx + 2] = l2;
      long l3 = (tmp[tmpIdx + 5] & MASK32_3) << 18;
      l3 |= (tmp[tmpIdx + 6] & MASK32_11) << 7;
      l3 |= (tmp[tmpIdx + 7] >>> 4) & MASK32_7;
      longs[longsIdx + 3] = l3;
      long l4 = (tmp[tmpIdx + 7] & MASK32_4) << 17;
      l4 |= (tmp[tmpIdx + 8] & MASK32_11) << 6;
      l4 |= (tmp[tmpIdx + 9] >>> 5) & MASK32_6;
      longs[longsIdx + 4] = l4;
      long l5 = (tmp[tmpIdx + 9] & MASK32_5) << 16;
      l5 |= (tmp[tmpIdx + 10] & MASK32_11) << 5;
      l5 |= (tmp[tmpIdx + 11] >>> 6) & MASK32_5;
      longs[longsIdx + 5] = l5;
      long l6 = (tmp[tmpIdx + 11] & MASK32_6) << 15;
      l6 |= (tmp[tmpIdx + 12] & MASK32_11) << 4;
      l6 |= (tmp[tmpIdx + 13] >>> 7) & MASK32_4;
      longs[longsIdx + 6] = l6;
      long l7 = (tmp[tmpIdx + 13] & MASK32_7) << 14;
      l7 |= (tmp[tmpIdx + 14] & MASK32_11) << 3;
      l7 |= (tmp[tmpIdx + 15] >>> 8) & MASK32_3;
      longs[longsIdx + 7] = l7;
      long l8 = (tmp[tmpIdx + 15] & MASK32_8) << 13;
      l8 |= (tmp[tmpIdx + 16] & MASK32_11) << 2;
      l8 |= (tmp[tmpIdx + 17] >>> 9) & MASK32_2;
      longs[longsIdx + 8] = l8;
      long l9 = (tmp[tmpIdx + 17] & MASK32_9) << 12;
      l9 |= (tmp[tmpIdx + 18] & MASK32_11) << 1;
      l9 |= (tmp[tmpIdx + 19] >>> 10) & MASK32_1;
      longs[longsIdx + 9] = l9;
      long l10 = (tmp[tmpIdx + 19] & MASK32_10) << 11;
      l10 |= (tmp[tmpIdx + 20] & MASK32_11) << 0;
      longs[longsIdx + 10] = l10;
    }
  }

  private static void decode22(DataInput in, long[] tmp, long[] longs) throws IOException {
    in.readLongs(tmp, 0, 44);
    shiftLongs(tmp, 44, longs, 0, 10, MASK32_22);
    for (int iter = 0, tmpIdx = 0, longsIdx = 44; iter < 4; ++iter, tmpIdx += 11, longsIdx += 5) {
      long l0 = (tmp[tmpIdx + 0] & MASK32_10) << 12;
      l0 |= (tmp[tmpIdx + 1] & MASK32_10) << 2;
      l0 |= (tmp[tmpIdx + 2] >>> 8) & MASK32_2;
      longs[longsIdx + 0] = l0;
      long l1 = (tmp[tmpIdx + 2] & MASK32_8) << 14;
      l1 |= (tmp[tmpIdx + 3] & MASK32_10) << 4;
      l1 |= (tmp[tmpIdx + 4] >>> 6) & MASK32_4;
      longs[longsIdx + 1] = l1;
      long l2 = (tmp[tmpIdx + 4] & MASK32_6) << 16;
      l2 |= (tmp[tmpIdx + 5] & MASK32_10) << 6;
      l2 |= (tmp[tmpIdx + 6] >>> 4) & MASK32_6;
      longs[longsIdx + 2] = l2;
      long l3 = (tmp[tmpIdx + 6] & MASK32_4) << 18;
      l3 |= (tmp[tmpIdx + 7] & MASK32_10) << 8;
      l3 |= (tmp[tmpIdx + 8] >>> 2) & MASK32_8;
      longs[longsIdx + 3] = l3;
      long l4 = (tmp[tmpIdx + 8] & MASK32_2) << 20;
      l4 |= (tmp[tmpIdx + 9] & MASK32_10) << 10;
      l4 |= (tmp[tmpIdx + 10] & MASK32_10) << 0;
      longs[longsIdx + 4] = l4;
    }
  }

  private static void decode23(DataInput in, long[] tmp, long[] longs) throws IOException {
    in.readLongs(tmp, 0, 46);
    shiftLongs(tmp, 46, longs, 0, 9, MASK32_23);
    for (int iter = 0, tmpIdx = 0, longsIdx = 46; iter < 2; ++iter, tmpIdx += 23, longsIdx += 9) {
      long l0 = (tmp[tmpIdx + 0] & MASK32_9) << 14;
      l0 |= (tmp[tmpIdx + 1] & MASK32_9) << 5;
      l0 |= (tmp[tmpIdx + 2] >>> 4) & MASK32_5;
      longs[longsIdx + 0] = l0;
      long l1 = (tmp[tmpIdx + 2] & MASK32_4) << 19;
      l1 |= (tmp[tmpIdx + 3] & MASK32_9) << 10;
      l1 |= (tmp[tmpIdx + 4] & MASK32_9) << 1;
      l1 |= (tmp[tmpIdx + 5] >>> 8) & MASK32_1;
      longs[longsIdx + 1] = l1;
      long l2 = (tmp[tmpIdx + 5] & MASK32_8) << 15;
      l2 |= (tmp[tmpIdx + 6] & MASK32_9) << 6;
      l2 |= (tmp[tmpIdx + 7] >>> 3) & MASK32_6;
      longs[longsIdx + 2] = l2;
      long l3 = (tmp[tmpIdx + 7] & MASK32_3) << 20;
      l3 |= (tmp[tmpIdx + 8] & MASK32_9) << 11;
      l3 |= (tmp[tmpIdx + 9] & MASK32_9) << 2;
      l3 |= (tmp[tmpIdx + 10] >>> 7) & MASK32_2;
      longs[longsIdx + 3] = l3;
      long l4 = (tmp[tmpIdx + 10] & MASK32_7) << 16;
      l4 |= (tmp[tmpIdx + 11] & MASK32_9) << 7;
      l4 |= (tmp[tmpIdx + 12] >>> 2) & MASK32_7;
      longs[longsIdx + 4] = l4;
      long l5 = (tmp[tmpIdx + 12] & MASK32_2) << 21;
      l5 |= (tmp[tmpIdx + 13] & MASK32_9) << 12;
      l5 |= (tmp[tmpIdx + 14] & MASK32_9) << 3;
      l5 |= (tmp[tmpIdx + 15] >>> 6) & MASK32_3;
      longs[longsIdx + 5] = l5;
      long l6 = (tmp[tmpIdx + 15] & MASK32_6) << 17;
      l6 |= (tmp[tmpIdx + 16] & MASK32_9) << 8;
      l6 |= (tmp[tmpIdx + 17] >>> 1) & MASK32_8;
      longs[longsIdx + 6] = l6;
      long l7 = (tmp[tmpIdx + 17] & MASK32_1) << 22;
      l7 |= (tmp[tmpIdx + 18] & MASK32_9) << 13;
      l7 |= (tmp[tmpIdx + 19] & MASK32_9) << 4;
      l7 |= (tmp[tmpIdx + 20] >>> 5) & MASK32_4;
      longs[longsIdx + 7] = l7;
      long l8 = (tmp[tmpIdx + 20] & MASK32_5) << 18;
      l8 |= (tmp[tmpIdx + 21] & MASK32_9) << 9;
      l8 |= (tmp[tmpIdx + 22] & MASK32_9) << 0;
      longs[longsIdx + 8] = l8;
    }
  }

  private static void decode24(DataInput in, long[] tmp, long[] longs) throws IOException {
    in.readLongs(tmp, 0, 48);
    shiftLongs(tmp, 48, longs, 0, 8, MASK32_24);
    shiftLongs(tmp, 48, tmp, 0, 0, MASK32_8);
    for (int iter = 0, tmpIdx = 0, longsIdx = 48; iter < 16; ++iter, tmpIdx += 3, longsIdx += 1) {
      long l0 = tmp[tmpIdx + 0] << 16;
      l0 |= tmp[tmpIdx + 1] << 8;
      l0 |= tmp[tmpIdx + 2] << 0;
      longs[longsIdx + 0] = l0;
    }
  }
}

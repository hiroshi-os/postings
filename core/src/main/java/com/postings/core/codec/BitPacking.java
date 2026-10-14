package com.postings.core.codec;

import com.postings.core.Constants;
import java.util.Arrays;

/**
 * Bit-packing codec for 128-element integer blocks (Frame of Reference).
 *
 * <p>Supports bit widths 0 to 32. Encodes 128 integers of width {@code b} into exactly {@code 16 *
 * b} bytes.
 */
public final class BitPacking {

  public static final int BLOCK_SIZE = Constants.BLOCK_SIZE; // 128

  private BitPacking() {}

  /** Calculates the minimum number of bits required to store an unsigned integer value. */
  public static int bitsRequired(int maxValue) {
    if (maxValue == 0) {
      return 0;
    }
    if (maxValue < 0) {
      return 32;
    }
    return 32 - Integer.numberOfLeadingZeros(maxValue);
  }

  /** Calculates byte size of 128 packed ints at given bit width. */
  public static int numBytes(int bitsPerValue) {
    if (bitsPerValue < 0 || bitsPerValue > 32) {
      throw new IllegalArgumentException("Invalid bit width: " + bitsPerValue);
    }
    return 16 * bitsPerValue;
  }

  /** Packs 128 integer values into the destination byte array. */
  public static void pack(
      int[] values, int valOffset, byte[] out, int outOffset, int bitsPerValue) {
    if (bitsPerValue < 0 || bitsPerValue > 32) {
      throw new IllegalArgumentException("Invalid bit width: " + bitsPerValue);
    }
    if (bitsPerValue == 0) {
      return;
    }

    if (bitsPerValue == 8) {
      for (int i = 0; i < BLOCK_SIZE; i++) {
        out[outOffset + i] = (byte) values[valOffset + i];
      }
      return;
    }

    if (bitsPerValue == 16) {
      for (int i = 0; i < BLOCK_SIZE; i++) {
        int v = values[valOffset + i];
        int idx = outOffset + (i << 1);
        out[idx] = (byte) v;
        out[idx + 1] = (byte) (v >>> 8);
      }
      return;
    }

    if (bitsPerValue == 32) {
      for (int i = 0; i < BLOCK_SIZE; i++) {
        int v = values[valOffset + i];
        int idx = outOffset + (i << 2);
        out[idx] = (byte) v;
        out[idx + 1] = (byte) (v >>> 8);
        out[idx + 2] = (byte) (v >>> 16);
        out[idx + 3] = (byte) (v >>> 24);
      }
      return;
    }

    long bitBuffer = 0;
    int bitsInBuffer = 0;
    int byteIdx = outOffset;
    long mask = (1L << bitsPerValue) - 1;

    for (int i = 0; i < BLOCK_SIZE; i++) {
      long val = values[valOffset + i] & mask;
      bitBuffer |= (val << bitsInBuffer);
      bitsInBuffer += bitsPerValue;
      while (bitsInBuffer >= 8) {
        out[byteIdx++] = (byte) bitBuffer;
        bitBuffer >>>= 8;
        bitsInBuffer -= 8;
      }
    }
    if (bitsInBuffer > 0) {
      out[byteIdx++] = (byte) bitBuffer;
    }
  }

  /**
   * Unpacks 128 integer values from the source byte array.
   *
   * @throws CorruptIndexException if input is truncated or bit width is invalid
   */
  public static void unpack(byte[] in, int inOffset, int[] out, int outOffset, int bitsPerValue)
      throws CorruptIndexException {
    if (bitsPerValue < 0 || bitsPerValue > 32) {
      throw new CorruptIndexException("Invalid bit width: " + bitsPerValue);
    }
    if (bitsPerValue == 0) {
      Arrays.fill(out, outOffset, outOffset + BLOCK_SIZE, 0);
      return;
    }

    int requiredBytes = 16 * bitsPerValue;
    if (in.length - inOffset < requiredBytes) {
      throw new CorruptIndexException(
          String.format(
              "Truncated packed block: needed %d bytes but only %d available",
              requiredBytes, in.length - inOffset));
    }

    if (bitsPerValue == 8) {
      for (int i = 0; i < BLOCK_SIZE; i++) {
        out[outOffset + i] = in[inOffset + i] & 0xFF;
      }
      return;
    }

    if (bitsPerValue == 16) {
      for (int i = 0; i < BLOCK_SIZE; i++) {
        int idx = inOffset + (i << 1);
        out[outOffset + i] = (in[idx] & 0xFF) | ((in[idx + 1] & 0xFF) << 8);
      }
      return;
    }

    if (bitsPerValue == 32) {
      for (int i = 0; i < BLOCK_SIZE; i++) {
        int idx = inOffset + (i << 2);
        out[outOffset + i] =
            (in[idx] & 0xFF)
                | ((in[idx + 1] & 0xFF) << 8)
                | ((in[idx + 2] & 0xFF) << 16)
                | ((in[idx + 3] & 0xFF) << 24);
      }
      return;
    }

    long bitBuffer = 0;
    int bitsInBuffer = 0;
    int byteIdx = inOffset;
    long mask = (1L << bitsPerValue) - 1;

    for (int i = 0; i < BLOCK_SIZE; i++) {
      while (bitsInBuffer < bitsPerValue) {
        bitBuffer |= ((long) (in[byteIdx++] & 0xFF)) << bitsInBuffer;
        bitsInBuffer += 8;
      }
      out[outOffset + i] = (int) (bitBuffer & mask);
      bitBuffer >>>= bitsPerValue;
      bitsInBuffer -= bitsPerValue;
    }
  }
}

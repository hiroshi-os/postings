package com.postings.core.codec;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Variable-length integer encoding (LEB128) with strict corruption validation. */
public final class VInt {

  private VInt() {}

  public static void writeVInt(DataOutput out, int value) throws IOException {
    while ((value & ~0x7F) != 0) {
      out.writeByte((byte) ((value & 0x7F) | 0x80));
      value >>>= 7;
    }
    out.writeByte((byte) value);
  }

  public static void writeVInt(ByteBuffer out, int value) {
    while ((value & ~0x7F) != 0) {
      out.put((byte) ((value & 0x7F) | 0x80));
      value >>>= 7;
    }
    out.put((byte) value);
  }

  public static int readVInt(DataInput in) throws IOException {
    byte b;
    try {
      b = in.readByte();
    } catch (EOFException e) {
      throw new CorruptIndexException("Unexpected EOF while reading VInt", e);
    }
    if ((b & 0x80) == 0) {
      return b;
    }
    int result = b & 0x7F;
    int shift = 7;
    while (true) {
      if (shift >= 35) {
        throw new CorruptIndexException("Invalid VInt encoding: shift exceeded 32 bits");
      }
      try {
        b = in.readByte();
      } catch (EOFException e) {
        throw new CorruptIndexException("Unexpected EOF while reading VInt continuation", e);
      }
      result |= (b & 0x7F) << shift;
      if ((b & 0x80) == 0) {
        return result;
      }
      shift += 7;
    }
  }

  public static int readVInt(ByteBuffer in) throws CorruptIndexException {
    if (!in.hasRemaining()) {
      throw new CorruptIndexException("Unexpected buffer underflow while reading VInt");
    }
    byte b = in.get();
    if ((b & 0x80) == 0) {
      return b;
    }
    int result = b & 0x7F;
    int shift = 7;
    while (true) {
      if (shift >= 35) {
        throw new CorruptIndexException("Invalid VInt encoding: shift exceeded 32 bits");
      }
      if (!in.hasRemaining()) {
        throw new CorruptIndexException("Buffer underflow while reading VInt continuation");
      }
      b = in.get();
      result |= (b & 0x7F) << shift;
      if ((b & 0x80) == 0) {
        return result;
      }
      shift += 7;
    }
  }

  public static void writeVLong(DataOutput out, long value) throws IOException {
    while ((value & ~0x7FL) != 0) {
      out.writeByte((byte) ((value & 0x7F) | 0x80));
      value >>>= 7;
    }
    out.writeByte((byte) value);
  }

  public static long readVLong(DataInput in) throws IOException {
    byte b;
    try {
      b = in.readByte();
    } catch (EOFException e) {
      throw new CorruptIndexException("Unexpected EOF while reading VLong", e);
    }
    if ((b & 0x80) == 0) {
      return b;
    }
    long result = b & 0x7FL;
    int shift = 7;
    while (true) {
      if (shift >= 70) {
        throw new CorruptIndexException("Invalid VLong encoding: shift exceeded 64 bits");
      }
      try {
        b = in.readByte();
      } catch (EOFException e) {
        throw new CorruptIndexException("Unexpected EOF while reading VLong continuation", e);
      }
      result |= ((long) (b & 0x7F)) << shift;
      if ((b & 0x80) == 0) {
        return result;
      }
      shift += 7;
    }
  }

  public static long readVLong(ByteBuffer in) throws CorruptIndexException {
    if (!in.hasRemaining()) {
      throw new CorruptIndexException("Unexpected buffer underflow while reading VLong");
    }
    byte b = in.get();
    if ((b & 0x80) == 0) {
      return b;
    }
    long result = b & 0x7FL;
    int shift = 7;
    while (true) {
      if (shift >= 70) {
        throw new CorruptIndexException("Invalid VLong encoding: shift exceeded 64 bits");
      }
      if (!in.hasRemaining()) {
        throw new CorruptIndexException("Buffer underflow while reading VLong continuation");
      }
      b = in.get();
      result |= ((long) (b & 0x7F)) << shift;
      if ((b & 0x80) == 0) {
        return result;
      }
      shift += 7;
    }
  }
}

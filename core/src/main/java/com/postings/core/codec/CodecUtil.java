package com.postings.core.codec;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.zip.CRC32;

/** Utility methods for index file headers, footers, and CRC32 verification. */
public final class CodecUtil {

  public static final int CODEC_MAGIC = 0x3FD05719;
  public static final int FOOTER_MAGIC = 0x50535447;
  public static final int VERSION_CURRENT = 1;
  public static final int FOOTER_LENGTH = 12; // 4 bytes footer magic + 8 bytes CRC32

  private CodecUtil() {}

  /** Writes standard file header. */
  public static void writeHeader(DataOutput out, int version) throws IOException {
    out.writeInt(CODEC_MAGIC);
    out.writeInt(version);
  }

  /** Reads and validates standard file header. */
  public static int checkHeader(DataInput in) throws IOException {
    int magic = in.readInt();
    if (magic != CODEC_MAGIC) {
      throw new CorruptIndexException(
          String.format("Invalid codec magic: 0x%08X (expected 0x%08X)", magic, CODEC_MAGIC));
    }
    int version = in.readInt();
    if (version != VERSION_CURRENT) {
      throw new CorruptIndexException("Unsupported codec version: " + version);
    }
    return version;
  }

  /** Writes standard footer with CRC32. */
  public static void writeFooter(DataOutput out, CRC32 crc) throws IOException {
    long crcValue = crc.getValue();
    out.writeInt(FOOTER_MAGIC);
    out.writeLong(crcValue);
  }

  /**
   * Verifies the CRC32 footer of an entire file channel.
   *
   * @throws CorruptIndexException if the file is too short, footer magic mismatches, or CRC fails
   */
  public static void verifyFooter(FileChannel channel) throws IOException {
    long size = channel.size();
    if (size < FOOTER_LENGTH + 8) { // header (8 bytes) + footer (12 bytes)
      throw new CorruptIndexException("File is too short to be a valid index file: " + size);
    }

    ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(size - FOOTER_LENGTH, 65536));
    CRC32 crc = new CRC32();
    long remaining = size - FOOTER_LENGTH;
    long position = 0;

    while (remaining > 0) {
      int toRead = (int) Math.min(remaining, buffer.capacity());
      buffer.limit(toRead);
      buffer.position(0);
      int read = channel.read(buffer, position);
      if (read < 0) {
        throw new CorruptIndexException("Unexpected end of file while verifying CRC");
      }
      crc.update(buffer.array(), 0, read);
      position += read;
      remaining -= read;
    }

    ByteBuffer footerBuf = ByteBuffer.allocate(FOOTER_LENGTH);
    channel.read(footerBuf, size - FOOTER_LENGTH);
    footerBuf.flip();

    int magic = footerBuf.getInt();
    if (magic != FOOTER_MAGIC) {
      throw new CorruptIndexException(
          String.format("Invalid footer magic: 0x%08X (expected 0x%08X)", magic, FOOTER_MAGIC));
    }
    long expectedCrc = footerBuf.getLong();
    long actualCrc = crc.getValue();
    if (actualCrc != expectedCrc) {
      throw new CorruptIndexException(
          String.format("CRC32 mismatch: calculated %d, expected %d", actualCrc, expectedCrc));
    }
  }
}

package com.postings.core.index;

import com.postings.core.codec.CodecUtil;
import com.postings.core.codec.CorruptIndexException;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.CheckedOutputStream;

/**
 * Live-documents bitset supporting mutations for deletions and generation file persistence (.del).
 */
public final class LiveDocs {

  private final int maxDoc;
  private final long[] bits;
  private int delCount;

  public LiveDocs(int maxDoc) {
    this.maxDoc = maxDoc;
    int numWords = (maxDoc + 63) >>> 6;
    this.bits = new long[numWords];
    this.delCount = 0;
  }

  public LiveDocs(int maxDoc, long[] bits, int delCount) {
    this.maxDoc = maxDoc;
    this.bits = bits;
    this.delCount = delCount;
  }

  /** Marks a document as deleted. Returns true if it was newly deleted. */
  public synchronized boolean delete(int docId) {
    if (docId < 0 || docId >= maxDoc) {
      throw new IndexOutOfBoundsException("docId " + docId + " out of bounds");
    }
    int wordIdx = docId >>> 6;
    long mask = 1L << docId;
    if ((bits[wordIdx] & mask) == 0) {
      bits[wordIdx] |= mask;
      delCount++;
      return true;
    }
    return false;
  }

  /** Returns true if the document is live (not deleted). */
  public synchronized boolean isLive(int docId) {
    if (docId < 0 || docId >= maxDoc) {
      return false;
    }
    int wordIdx = docId >>> 6;
    long mask = 1L << docId;
    return (bits[wordIdx] & mask) == 0;
  }

  public synchronized int delCount() {
    return delCount;
  }

  public synchronized int liveCount() {
    return maxDoc - delCount;
  }

  public int maxDoc() {
    return maxDoc;
  }

  public synchronized boolean hasDeletions() {
    return delCount > 0;
  }

  public synchronized LiveDocs copy() {
    long[] copyBits = Arrays.copyOf(bits, bits.length);
    return new LiveDocs(maxDoc, copyBits, delCount);
  }

  public static void write(Path file, LiveDocs liveDocs) throws IOException {
    CRC32 crc = new CRC32();
    try (BufferedOutputStream bos = new BufferedOutputStream(Files.newOutputStream(file));
        CheckedOutputStream cos = new CheckedOutputStream(bos, crc);
        DataOutputStream out = new DataOutputStream(cos)) {

      CodecUtil.writeHeader(out, CodecUtil.VERSION_CURRENT);
      out.writeInt(liveDocs.maxDoc);
      out.writeInt(liveDocs.delCount);
      out.writeInt(liveDocs.bits.length);
      for (long word : liveDocs.bits) {
        out.writeLong(word);
      }
      CodecUtil.writeFooter(out, crc);
    }
  }

  public static LiveDocs read(Path file) throws IOException {
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
      CodecUtil.verifyFooter(channel);
    }

    try (DataInputStream in =
        new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
      CodecUtil.checkHeader(in);
      int maxDoc = in.readInt();
      int delCount = in.readInt();
      int numWords = in.readInt();
      int expectedWords = (maxDoc + 63) >>> 6;
      if (numWords != expectedWords) {
        throw new CorruptIndexException("Corrupt live-docs bitset size: " + numWords);
      }
      long[] bits = new long[numWords];
      for (int i = 0; i < numWords; i++) {
        bits[i] = in.readLong();
      }
      return new LiveDocs(maxDoc, bits, delCount);
    }
  }
}

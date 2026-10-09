package com.postings.core.index;

import com.postings.core.codec.CodecUtil;
import com.postings.core.codec.CorruptIndexException;
import com.postings.core.codec.VInt;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Reader for front-coded term dictionary (.dict) with binary search over the in-memory block index.
 */
public final class DictionaryReader implements AutoCloseable {

  private final FileChannel channel;
  private final int numTerms;
  private final int numBlocks;
  private final String[] firstTerms;
  private final long[] blockOffsets;

  public DictionaryReader(Path file) throws IOException {
    this.channel = FileChannel.open(file, StandardOpenOption.READ);
    CodecUtil.verifyFooter(channel);

    long size = channel.size();
    ByteBuffer headerBuf = ByteBuffer.allocate(16);
    channel.read(headerBuf, 0);
    headerBuf.flip();

    int magic = headerBuf.getInt();
    if (magic != CodecUtil.CODEC_MAGIC) {
      throw new CorruptIndexException("Invalid codec magic: " + magic);
    }
    int version = headerBuf.getInt();
    if (version != CodecUtil.VERSION_CURRENT) {
      throw new CorruptIndexException("Unsupported codec version: " + version);
    }
    this.numTerms = headerBuf.getInt();
    this.numBlocks = headerBuf.getInt();

    if (numBlocks == 0) {
      this.firstTerms = new String[0];
      this.blockOffsets = new long[0];
      return;
    }

    // Read indexOffset pointer from right before the footer
    long indexPointerOffset = size - CodecUtil.FOOTER_LENGTH - 8;
    ByteBuffer ptrBuf = ByteBuffer.allocate(8);
    channel.read(ptrBuf, indexPointerOffset);
    ptrBuf.flip();
    long indexOffset = ptrBuf.getLong();

    int indexByteLength = (int) (indexPointerOffset - indexOffset);
    ByteBuffer indexBuf = ByteBuffer.allocate(indexByteLength);
    channel.read(indexBuf, indexOffset);
    indexBuf.flip();

    this.firstTerms = new String[numBlocks];
    this.blockOffsets = new long[numBlocks];

    for (int b = 0; b < numBlocks; b++) {
      int termLen = VInt.readVInt(indexBuf);
      byte[] termBytes = new byte[termLen];
      indexBuf.get(termBytes);
      firstTerms[b] = new String(termBytes, StandardCharsets.UTF_8);
      blockOffsets[b] = indexBuf.getLong();
    }
  }

  public int numTerms() {
    return numTerms;
  }

  public TermEntry lookup(String term) throws IOException {
    if (numTerms == 0 || term == null || term.isEmpty()) {
      return null;
    }

    // Binary search the in-memory block index
    int low = 0;
    int high = numBlocks - 1;
    int targetBlock = -1;

    while (low <= high) {
      int mid = (low + high) >>> 1;
      int cmp = firstTerms[mid].compareTo(term);
      if (cmp <= 0) {
        targetBlock = mid;
        low = mid + 1; // explore if a later block also starts <= term
      } else {
        high = mid - 1;
      }
    }

    if (targetBlock < 0) {
      return null; // term is smaller than the very first term
    }

    long startOffset = blockOffsets[targetBlock];
    long endOffset =
        (targetBlock + 1 < numBlocks)
            ? blockOffsets[targetBlock + 1]
            : (channel.size() - CodecUtil.FOOTER_LENGTH - 8 - (numBlocks * 16L)); // upper bound

    int bufferSize = (int) Math.min(Math.max(endOffset - startOffset, 512), 65536);
    ByteBuffer buf = ByteBuffer.allocate(bufferSize);
    channel.read(buf, startOffset);
    buf.flip();

    // Term 0 of block
    int term0Len = VInt.readVInt(buf);
    byte[] prevBytes = new byte[term0Len];
    buf.get(prevBytes);
    int df0 = VInt.readVInt(buf);
    long offset0 = VInt.readVLong(buf);
    float maxScore0 = buf.getFloat();

    String currentTerm = new String(prevBytes, StandardCharsets.UTF_8);
    int cmp0 = currentTerm.compareTo(term);
    if (cmp0 == 0) {
      return new TermEntry(currentTerm, df0, offset0, maxScore0);
    }
    if (cmp0 > 0) {
      return null;
    }

    int remainingTermsInBlock =
        Math.min(
                DictionaryWriter.BLOCK_SIZE, numTerms - (targetBlock * DictionaryWriter.BLOCK_SIZE))
            - 1;

    for (int i = 0; i < remainingTermsInBlock; i++) {
      int prefixLen = VInt.readVInt(buf);
      int suffixLen = VInt.readVInt(buf);
      byte[] currentBytes = new byte[prefixLen + suffixLen];
      System.arraycopy(prevBytes, 0, currentBytes, 0, prefixLen);
      buf.get(currentBytes, prefixLen, suffixLen);

      int df = VInt.readVInt(buf);
      long offset = VInt.readVLong(buf);
      float maxScore = buf.getFloat();

      currentTerm = new String(currentBytes, StandardCharsets.UTF_8);
      int cmp = currentTerm.compareTo(term);
      if (cmp == 0) {
        return new TermEntry(currentTerm, df, offset, maxScore);
      }
      if (cmp > 0) {
        return null;
      }

      prevBytes = currentBytes;
    }

    return null;
  }

  @Override
  public void close() throws IOException {
    channel.close();
  }
}

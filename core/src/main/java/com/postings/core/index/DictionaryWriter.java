package com.postings.core.index;

import com.postings.core.codec.CodecUtil;
import com.postings.core.codec.VInt;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.CheckedOutputStream;

/** Front-coded term dictionary (.dict) with in-memory block index (blocks of 32 terms). */
public final class DictionaryWriter {

  public static final int BLOCK_SIZE = 32;

  private DictionaryWriter() {}

  public static void write(Path file, List<TermEntry> sortedTerms) throws IOException {
    CRC32 crc = new CRC32();
    int numTerms = sortedTerms.size();
    int numBlocks = (numTerms + BLOCK_SIZE - 1) / BLOCK_SIZE;

    ByteArrayOutputStream payloadBaos = new ByteArrayOutputStream();
    DataOutputStream payloadOut = new DataOutputStream(payloadBaos);

    ByteArrayOutputStream indexBaos = new ByteArrayOutputStream();
    DataOutputStream indexOut = new DataOutputStream(indexBaos);

    long payloadBaseOffset = 16; // magic (4) + version (4) + numTerms (4) + numBlocks (4)

    for (int b = 0; b < numBlocks; b++) {
      int start = b * BLOCK_SIZE;
      int end = Math.min(start + BLOCK_SIZE, numTerms);
      TermEntry first = sortedTerms.get(start);

      long blockOffset = payloadBaseOffset + payloadBaos.size();

      // Write block index entry
      byte[] firstBytes = first.term().getBytes(StandardCharsets.UTF_8);
      VInt.writeVInt(indexOut, firstBytes.length);
      indexOut.write(firstBytes);
      indexOut.writeLong(blockOffset);

      // Term 0 of block
      VInt.writeVInt(payloadOut, firstBytes.length);
      payloadOut.write(firstBytes);
      VInt.writeVInt(payloadOut, first.df());
      VInt.writeVLong(payloadOut, first.postingsOffset());
      payloadOut.writeFloat(first.maxScore());

      byte[] prevBytes = firstBytes;

      // Terms 1..31 of block
      for (int i = start + 1; i < end; i++) {
        TermEntry entry = sortedTerms.get(i);
        byte[] currentBytes = entry.term().getBytes(StandardCharsets.UTF_8);

        int prefixLen = commonPrefixLength(prevBytes, currentBytes);
        int suffixLen = currentBytes.length - prefixLen;

        VInt.writeVInt(payloadOut, prefixLen);
        VInt.writeVInt(payloadOut, suffixLen);
        payloadOut.write(currentBytes, prefixLen, suffixLen);
        VInt.writeVInt(payloadOut, entry.df());
        VInt.writeVLong(payloadOut, entry.postingsOffset());
        payloadOut.writeFloat(entry.maxScore());

        prevBytes = currentBytes;
      }
    }

    payloadOut.flush();
    indexOut.flush();

    byte[] payloadData = payloadBaos.toByteArray();
    byte[] indexData = indexBaos.toByteArray();

    try (BufferedOutputStream bos = new BufferedOutputStream(Files.newOutputStream(file));
        CheckedOutputStream cos = new CheckedOutputStream(bos, crc);
        DataOutputStream out = new DataOutputStream(cos)) {

      CodecUtil.writeHeader(out, CodecUtil.VERSION_CURRENT);
      out.writeInt(numTerms);
      out.writeInt(numBlocks);

      // Write blocks payload
      out.write(payloadData);

      // Write in-memory index table data and pointer
      long indexOffset = payloadBaseOffset + payloadData.length;
      out.write(indexData);
      out.writeLong(indexOffset);

      CodecUtil.writeFooter(out, crc);
    }
  }

  private static int commonPrefixLength(byte[] a, byte[] b) {
    int max = Math.min(a.length, b.length);
    int i = 0;
    while (i < max && a[i] == b[i]) {
      i++;
    }
    return i;
  }
}

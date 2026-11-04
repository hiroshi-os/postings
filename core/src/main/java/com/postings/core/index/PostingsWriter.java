package com.postings.core.index;

import com.postings.core.codec.BitPacking;
import com.postings.core.codec.CodecUtil;
import com.postings.core.codec.VInt;
import com.postings.core.search.BM25Scorer;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.CheckedOutputStream;

/**
 * Writes compressed inverted postings (.post file) using bit-packing (Frame of Reference) for
 * blocks of 128 docs and VInt for tail blocks.
 */
public final class PostingsWriter implements AutoCloseable {

  private final DataOutputStream out;
  private final CRC32 crc;
  private final BM25Scorer scorer;
  private long currentOffset;

  private final int[] blockDeltas = new int[BitPacking.BLOCK_SIZE];
  private final int[] blockFreqDeltas = new int[BitPacking.BLOCK_SIZE];
  private final byte[] packedBuffer = new byte[2048];

  public PostingsWriter(Path file, BM25Scorer scorer, float avgDocLen) throws IOException {
    this.crc = new CRC32();
    BufferedOutputStream bos = new BufferedOutputStream(Files.newOutputStream(file));
    CheckedOutputStream cos = new CheckedOutputStream(bos, crc);
    this.out = new DataOutputStream(cos);
    this.scorer = scorer;

    CodecUtil.writeHeader(out, CodecUtil.VERSION_CURRENT);
    this.currentOffset = 8; // magic (4) + version (4)
  }

  public record PostingsMeta(long offset, float maxScore) {}

  /**
   * Writes postings for a single term.
   *
   * @param docIds dense sorted doc IDs
   * @param freqs term frequencies per doc
   * @param docLengths collection doc lengths
   * @param idf term IDF
   * @return offset in .post file and global max BM25 score
   */
  public PostingsMeta writePostings(
      int[] docIds, int[] freqs, int size, int[] docLengths, float idf) throws IOException {
    long postingsStartOffset = currentOffset;
    float globalMaxScore = 0.0f;

    int numBlocks = size / BitPacking.BLOCK_SIZE;
    int remainder = size % BitPacking.BLOCK_SIZE;
    int totalBlocks = numBlocks + (remainder > 0 ? 1 : 0);

    // Write total number of blocks for this term
    out.writeInt(totalBlocks);
    currentOffset += 4;

    int prevBlockLastDoc = -1;
    int docIdx = 0;

    // Full blocks of 128
    for (int b = 0; b < numBlocks; b++) {
      int blockStart = docIdx;
      int blockEnd = blockStart + BitPacking.BLOCK_SIZE;
      int lastDocId = docIds[blockEnd - 1];

      int maxDocDelta = 0;
      int maxFreqDelta = 0;
      int maxDocTf = 1;

      for (int i = 0; i < BitPacking.BLOCK_SIZE; i++) {
        int d = docIds[blockStart + i];
        int f = freqs[blockStart + i];
        if (f > maxDocTf) {
          maxDocTf = f;
        }

        int delta;
        if (i == 0) {
          delta = (prevBlockLastDoc < 0) ? d : (d - prevBlockLastDoc - 1);
        } else {
          delta = d - docIds[blockStart + i - 1] - 1;
        }
        int freqDelta = f - 1;

        blockDeltas[i] = delta;
        blockFreqDeltas[i] = freqDelta;

        if (delta > maxDocDelta) {
          maxDocDelta = delta;
        }
        if (freqDelta > maxFreqDelta) {
          maxFreqDelta = freqDelta;
        }
      }

      float k1 = scorer.params().k1();
      float bParam = scorer.params().b();
      float blockMaxImpact = (maxDocTf * (k1 + 1.0f)) / (maxDocTf + k1 * (1.0f - bParam));

      if (blockMaxImpact > globalMaxScore) {
        globalMaxScore = blockMaxImpact;
      }

      int bitsPerDoc = BitPacking.bitsRequired(maxDocDelta);
      int bitsPerFreq = BitPacking.bitsRequired(maxFreqDelta);
      int docBytes = BitPacking.numBytes(bitsPerDoc);
      int freqBytes = BitPacking.numBytes(bitsPerFreq);
      int payloadLength = 2 + docBytes + freqBytes;

      // Pack payload
      packedBuffer[0] = (byte) bitsPerDoc;
      packedBuffer[1] = (byte) bitsPerFreq;
      BitPacking.pack(blockDeltas, 0, packedBuffer, 2, bitsPerDoc);
      BitPacking.pack(blockFreqDeltas, 0, packedBuffer, 2 + docBytes, bitsPerFreq);

      // Write block header: lastDocId (4), docCount (4), payloadLength (4), blockMaxImpact (4)
      out.writeInt(lastDocId);
      out.writeInt(BitPacking.BLOCK_SIZE);
      out.writeInt(payloadLength);
      out.writeFloat(blockMaxImpact);
      // Write payload
      out.write(packedBuffer, 0, payloadLength);

      currentOffset += 16 + payloadLength;
      prevBlockLastDoc = lastDocId;
      docIdx = blockEnd;
    }

    // Remainder tail block
    if (remainder > 0) {
      int blockStart = docIdx;
      int lastDocId = docIds[size - 1];

      ByteArrayOutputStream baos = new ByteArrayOutputStream(remainder * 10);
      DataOutputStream tailOut = new DataOutputStream(baos);
      tailOut.writeByte((byte) remainder);

      int maxDocTf = 1;
      for (int i = 0; i < remainder; i++) {
        int d = docIds[blockStart + i];
        int f = freqs[blockStart + i];
        if (f > maxDocTf) {
          maxDocTf = f;
        }

        int delta;
        if (i == 0) {
          delta = (prevBlockLastDoc < 0) ? d : (d - prevBlockLastDoc - 1);
        } else {
          delta = d - docIds[blockStart + i - 1] - 1;
        }
        int freqDelta = f - 1;

        VInt.writeVInt(tailOut, delta);
        VInt.writeVInt(tailOut, freqDelta);
      }

      float k1 = scorer.params().k1();
      float bParam = scorer.params().b();
      float blockMaxImpact = (maxDocTf * (k1 + 1.0f)) / (maxDocTf + k1 * (1.0f - bParam));

      if (blockMaxImpact > globalMaxScore) {
        globalMaxScore = blockMaxImpact;
      }

      tailOut.flush();
      byte[] tailPayload = baos.toByteArray();
      int payloadLength = tailPayload.length;

      // Write tail block header: lastDocId (4), docCount (4), payloadLength (4), blockMaxImpact (4)
      out.writeInt(lastDocId);
      out.writeInt(remainder);
      out.writeInt(payloadLength);
      out.writeFloat(blockMaxImpact);
      // Write tail payload
      out.write(tailPayload);

      currentOffset += 16 + payloadLength;
    }

    return new PostingsMeta(postingsStartOffset, globalMaxScore);
  }

  @Override
  public void close() throws IOException {
    CodecUtil.writeFooter(out, crc);
    out.flush();
    out.close();
  }
}

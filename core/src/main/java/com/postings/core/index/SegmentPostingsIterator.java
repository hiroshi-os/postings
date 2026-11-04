package com.postings.core.index;

import com.postings.core.codec.BitPacking;
import com.postings.core.codec.VInt;
import com.postings.core.search.PostingsIterator;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/** Iterates postings from a segment's .post file with skip-block advance and Block-Max metrics. */
public final class SegmentPostingsIterator implements PostingsIterator {

  private final FileChannel channel;
  private final int totalBlocks;
  private final float globalMaxScore;
  private final LiveDocs liveDocs;

  private int blocksRead = 0;
  private int currentDocId = -1;
  private int currentFreq = 0;

  // Current block metadata
  private int curBlockLastDoc = -1;
  private int curBlockDocCount = 0;
  private int curBlockPayloadLen = 0;
  private float curBlockMaxScore = 0.0f;
  private boolean curBlockDecoded = false;
  private long curBlockPayloadFileOffset = 0;

  // Decoded block buffer
  private final int[] blockDocIds = new int[BitPacking.BLOCK_SIZE];
  private final int[] blockFreqs = new int[BitPacking.BLOCK_SIZE];
  private int blockDocCount = 0;
  private int blockCursor = -1;
  private int prevBlockLastDoc = -1;

  private final ByteBuffer headerBuf = ByteBuffer.allocate(16);

  private final float idf;

  public SegmentPostingsIterator(
      FileChannel channel, long postingsOffset, float globalMaxScore, LiveDocs liveDocs, float idf)
      throws IOException {
    this.channel = channel;
    this.globalMaxScore = globalMaxScore;
    this.liveDocs = liveDocs;
    this.idf = (idf <= 0.0f) ? 1.0f : idf;

    ByteBuffer totalBlocksBuf = ByteBuffer.allocate(4);
    channel.read(totalBlocksBuf, postingsOffset);
    totalBlocksBuf.flip();
    this.totalBlocks = totalBlocksBuf.getInt();

    this.curBlockPayloadFileOffset = postingsOffset + 4;
    loadNextBlockHeader();
  }

  public SegmentPostingsIterator(
      FileChannel channel, long postingsOffset, float globalMaxScore, LiveDocs liveDocs)
      throws IOException {
    this(channel, postingsOffset, globalMaxScore, liveDocs, 1.0f);
  }

  public SegmentPostingsIterator(FileChannel channel, long postingsOffset, float globalMaxScore)
      throws IOException {
    this(channel, postingsOffset, globalMaxScore, null, 1.0f);
  }

  private boolean loadNextBlockHeader() throws IOException {
    if (blocksRead >= totalBlocks) {
      curBlockLastDoc = NO_MORE_DOCS;
      curBlockMaxScore = 0.0f;
      return false;
    }

    headerBuf.clear();
    channel.read(headerBuf, curBlockPayloadFileOffset);
    headerBuf.flip();

    curBlockLastDoc = headerBuf.getInt();
    curBlockDocCount = headerBuf.getInt();
    curBlockPayloadLen = headerBuf.getInt();
    curBlockMaxScore = headerBuf.getFloat();
    curBlockPayloadFileOffset += 16; // now points to payload start
    curBlockDecoded = false;
    blocksRead++;
    return true;
  }

  private void decodeCurrentBlock() throws IOException {
    if (curBlockDecoded) {
      return;
    }

    ByteBuffer payloadBuf = ByteBuffer.allocate(curBlockPayloadLen);
    channel.read(payloadBuf, curBlockPayloadFileOffset);
    payloadBuf.flip();

    // Next block header offset will be after this payload
    curBlockPayloadFileOffset += curBlockPayloadLen;

    if (curBlockDocCount == BitPacking.BLOCK_SIZE) {
      // Full block of 128
      int bitsPerDoc = payloadBuf.get() & 0xFF;
      int bitsPerFreq = payloadBuf.get() & 0xFF;
      int docBytes = BitPacking.numBytes(bitsPerDoc);

      int[] docDeltas = new int[BitPacking.BLOCK_SIZE];
      int[] freqDeltas = new int[BitPacking.BLOCK_SIZE];

      byte[] payloadBytes = payloadBuf.array();
      BitPacking.unpack(payloadBytes, 2, docDeltas, 0, bitsPerDoc);
      BitPacking.unpack(payloadBytes, 2 + docBytes, freqDeltas, 0, bitsPerFreq);

      int runningDoc =
          (prevBlockLastDoc < 0) ? docDeltas[0] : (prevBlockLastDoc + 1 + docDeltas[0]);
      blockDocIds[0] = runningDoc;
      blockFreqs[0] = freqDeltas[0] + 1;

      for (int i = 1; i < BitPacking.BLOCK_SIZE; i++) {
        runningDoc += 1 + docDeltas[i];
        blockDocIds[i] = runningDoc;
        blockFreqs[i] = freqDeltas[i] + 1;
      }
      blockDocCount = BitPacking.BLOCK_SIZE;
    } else {
      // Tail block
      int count = payloadBuf.get() & 0xFF;
      int runningDoc = 0;
      for (int i = 0; i < count; i++) {
        int delta = VInt.readVInt(payloadBuf);
        int freqDelta = VInt.readVInt(payloadBuf);
        if (i == 0) {
          runningDoc = (prevBlockLastDoc < 0) ? delta : (prevBlockLastDoc + 1 + delta);
        } else {
          runningDoc += 1 + delta;
        }
        blockDocIds[i] = runningDoc;
        blockFreqs[i] = freqDelta + 1;
      }
      blockDocCount = count;
    }

    prevBlockLastDoc = curBlockLastDoc;
    curBlockDecoded = true;
    blockCursor = -1;
  }

  @Override
  public int docId() {
    return currentDocId;
  }

  @Override
  public int freq() {
    return currentFreq;
  }

  @Override
  public float blockMaxScore() {
    return curBlockMaxScore * idf;
  }

  @Override
  public int blockLastDoc() {
    return curBlockLastDoc;
  }

  @Override
  public float maxScore() {
    return globalMaxScore * idf;
  }

  @Override
  public int nextDoc() {
    try {
      while (true) {
        if (!curBlockDecoded) {
          if (blocksRead > totalBlocks) {
            currentDocId = NO_MORE_DOCS;
            return NO_MORE_DOCS;
          }
          decodeCurrentBlock();
        }

        blockCursor++;
        if (blockCursor < blockDocCount) {
          currentDocId = blockDocIds[blockCursor];
          currentFreq = blockFreqs[blockCursor];
          if (liveDocs != null && !liveDocs.isLive(currentDocId)) {
            continue;
          }
          return currentDocId;
        }

        // Current block exhausted, load next block
        if (blocksRead >= totalBlocks) {
          currentDocId = NO_MORE_DOCS;
          return NO_MORE_DOCS;
        }

        loadNextBlockHeader();
        decodeCurrentBlock();
      }
    } catch (IOException e) {
      throw new RuntimeException("Error reading postings", e);
    }
  }

  @Override
  public int advance(int target) {
    if (target <= currentDocId) {
      return currentDocId;
    }
    if (currentDocId == NO_MORE_DOCS) {
      return NO_MORE_DOCS;
    }

    try {
      // Skip blocks where lastDocId < target
      while (target > curBlockLastDoc) {
        if (!curBlockDecoded) {
          // Skip payload without decoding!
          curBlockPayloadFileOffset += curBlockPayloadLen;
          prevBlockLastDoc = curBlockLastDoc;
        }

        if (blocksRead >= totalBlocks) {
          currentDocId = NO_MORE_DOCS;
          return NO_MORE_DOCS;
        }

        if (!loadNextBlockHeader()) {
          currentDocId = NO_MORE_DOCS;
          return NO_MORE_DOCS;
        }
      }

      // Now target <= curBlockLastDoc
      decodeCurrentBlock();

      // Scan within this block
      while (blockCursor + 1 < blockDocCount && blockDocIds[blockCursor + 1] < target) {
        blockCursor++;
      }

      return nextDoc();
    } catch (IOException e) {
      throw new RuntimeException("Error advancing postings", e);
    }
  }
}

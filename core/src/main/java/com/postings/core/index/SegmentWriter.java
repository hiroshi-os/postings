package com.postings.core.index;

import com.postings.core.search.BM25Scorer;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Flushes an in-memory index buffer into immutable segment files (.len, .stored, .post, .dict) and
 * fsyncs them.
 */
public final class SegmentWriter {

  private SegmentWriter() {}

  public static void writeSegment(
      Path dir, String segmentName, MemoryIndexBuffer buffer, BM25Scorer scorer)
      throws IOException {
    int numDocs = buffer.numDocs();
    int[] docLengths = buffer.docLengths();
    long totalTokens = 0;
    for (int l : docLengths) {
      totalTokens += l;
    }
    float avgdl = (numDocs > 0) ? ((float) totalTokens / (float) numDocs) : 0.0f;

    Path lenFile = dir.resolve(segmentName + ".len");
    Path storedFile = dir.resolve(segmentName + ".stored");
    Path postFile = dir.resolve(segmentName + ".post");
    Path dictFile = dir.resolve(segmentName + ".dict");

    // 1. Write .len
    DocLengthsWriter.write(lenFile, docLengths);
    fsync(lenFile);

    // 2. Write .stored
    StoredFieldsWriter.write(storedFile, buffer.docIds(), buffer.docTitles());
    fsync(storedFile);

    // 3. Write .post and collect term entries
    List<TermEntry> termEntries = new ArrayList<>();
    try (PostingsWriter postWriter = new PostingsWriter(postFile, scorer, avgdl)) {
      for (Map.Entry<String, MemoryIndexBuffer.PostingListBuilder> entry :
          buffer.terms().entrySet()) {
        String term = entry.getKey();
        MemoryIndexBuffer.PostingListBuilder list = entry.getValue();
        int df = list.size();
        float idf = BM25Scorer.idf(numDocs, df);

        PostingsWriter.PostingsMeta meta =
            postWriter.writePostings(list.docIds(), list.freqs(), list.size(), docLengths, idf);

        termEntries.add(new TermEntry(term, df, meta.offset(), meta.maxScore()));
      }
    }
    fsync(postFile);

    // 4. Write .dict
    DictionaryWriter.write(dictFile, termEntries);
    fsync(dictFile);
  }

  private static void fsync(Path path) throws IOException {
    try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
      channel.force(true);
    }
  }
}

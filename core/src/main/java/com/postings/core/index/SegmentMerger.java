package com.postings.core.index;

import com.postings.core.model.Document;
import com.postings.core.search.BM25Scorer;
import com.postings.core.search.PostingsIterator;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

/**
 * Merges multiple segments into a single new segment, dropping deleted documents and renumbering
 * doc IDs.
 */
public final class SegmentMerger {

  private SegmentMerger() {}

  /** Result of a merge operation. */
  public record MergeResult(String segmentName, int docCount, long totalBytes) {}

  /**
   * Merges the specified segment readers into a new segment.
   *
   * @param dir Directory where segments reside
   * @param newSegmentName Name of the new merged segment
   * @param readers List of SegmentReaders to merge (in segment order)
   * @param scorer Scorer used for BM25 impact calculation
   * @return MergeResult containing new segment info, or null if all documents were deleted
   */
  public static MergeResult merge(
      Path dir, String newSegmentName, List<SegmentReader> readers, BM25Scorer scorer)
      throws IOException {
    // 1. Build docId mappings and collect doc lengths and stored fields
    List<int[]> docMaps = new ArrayList<>(readers.size());
    int totalLiveDocs = 0;

    for (SegmentReader sr : readers) {
      int maxDoc = sr.maxDoc();
      int[] docMap = new int[maxDoc];
      Arrays.fill(docMap, -1);
      LiveDocs liveDocs = sr.liveDocs();

      for (int d = 0; d < maxDoc; d++) {
        if (liveDocs.isLive(d)) {
          docMap[d] = totalLiveDocs++;
        }
      }
      docMaps.add(docMap);
    }

    if (totalLiveDocs == 0) {
      return null;
    }

    int[] mergedDocLengths = new int[totalLiveDocs];
    List<String> mergedDocIds = new ArrayList<>(totalLiveDocs);
    List<String> mergedDocTitles = new ArrayList<>(totalLiveDocs);

    long totalTokens = 0;
    for (int s = 0; s < readers.size(); s++) {
      SegmentReader sr = readers.get(s);
      int[] docMap = docMaps.get(s);
      int maxDoc = sr.maxDoc();

      for (int d = 0; d < maxDoc; d++) {
        int newDoc = docMap[d];
        if (newDoc >= 0) {
          int len = sr.docLength(d);
          mergedDocLengths[newDoc] = len;
          totalTokens += len;

          Document doc = sr.document(d);
          mergedDocIds.add(doc.id());
          mergedDocTitles.add(doc.title());
        }
      }
    }

    float avgdl = (float) totalTokens / (float) totalLiveDocs;

    Path lenFile = dir.resolve(newSegmentName + ".len");
    Path storedFile = dir.resolve(newSegmentName + ".stored");
    Path postFile = dir.resolve(newSegmentName + ".post");
    Path dictFile = dir.resolve(newSegmentName + ".dict");

    // 2. Write .len
    DocLengthsWriter.write(lenFile, mergedDocLengths);
    fsync(lenFile);

    // 3. Write .stored
    StoredFieldsWriter.write(storedFile, mergedDocIds, mergedDocTitles);
    fsync(storedFile);

    // 4. Collect all unique terms across dictionaries
    TreeSet<String> allTerms = new TreeSet<>();
    for (SegmentReader sr : readers) {
      sr.dictReader().forEachTerm(entry -> allTerms.add(entry.term()));
    }

    // 5. Merge postings for each term
    List<TermEntry> termEntries = new ArrayList<>();
    try (PostingsWriter postWriter = new PostingsWriter(postFile, scorer, avgdl)) {
      int[] docIdBuffer = new int[128];
      int[] freqBuffer = new int[128];

      for (String term : allTerms) {
        int termDocCount = 0;

        for (int s = 0; s < readers.size(); s++) {
          SegmentReader sr = readers.get(s);
          int[] docMap = docMaps.get(s);
          PostingsIterator it = sr.postings(term);
          if (it == null) {
            continue;
          }

          int doc;
          while ((doc = it.nextDoc()) != PostingsIterator.NO_MORE_DOCS) {
            int newDoc = docMap[doc];
            if (newDoc >= 0) {
              if (termDocCount == docIdBuffer.length) {
                int newCap = docIdBuffer.length * 2;
                docIdBuffer = Arrays.copyOf(docIdBuffer, newCap);
                freqBuffer = Arrays.copyOf(freqBuffer, newCap);
              }
              docIdBuffer[termDocCount] = newDoc;
              freqBuffer[termDocCount] = it.freq();
              termDocCount++;
            }
          }
        }

        if (termDocCount > 0) {
          float idf = BM25Scorer.idf(totalLiveDocs, termDocCount);
          PostingsWriter.PostingsMeta meta =
              postWriter.writePostings(
                  docIdBuffer, freqBuffer, termDocCount, mergedDocLengths, idf);
          termEntries.add(new TermEntry(term, termDocCount, meta.offset(), meta.maxScore()));
        }
      }
    }
    fsync(postFile);

    // 6. Write .dict
    DictionaryWriter.write(dictFile, termEntries);
    fsync(dictFile);

    long totalBytes =
        java.nio.file.Files.size(lenFile)
            + java.nio.file.Files.size(storedFile)
            + java.nio.file.Files.size(postFile)
            + java.nio.file.Files.size(dictFile);

    return new MergeResult(newSegmentName, totalLiveDocs, totalBytes);
  }

  private static void fsync(Path path) throws IOException {
    try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
      channel.force(true);
    }
  }
}

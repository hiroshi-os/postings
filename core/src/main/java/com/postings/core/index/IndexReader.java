package com.postings.core.index;

import com.postings.core.model.Document;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Point-in-time snapshot reader across multiple immutable segments. Computes collection-wide
 * statistics (N and avgdl) across live docs at reader-open time.
 */
public final class IndexReader implements AutoCloseable {

  private final List<SegmentReader> segmentReaders;
  private final long generation;
  private final int totalDocs;
  private final float avgDocLength;
  private final AtomicInteger refCount = new AtomicInteger(1);
  private volatile boolean closed = false;

  public IndexReader(List<SegmentReader> readers, long generation) {
    this.segmentReaders = new ArrayList<>(readers);
    this.generation = generation;

    int totalLive = 0;
    long totalTokens = 0;

    for (SegmentReader sr : readers) {
      LiveDocs live = sr.liveDocs();
      int[] lengths = sr.docLengths();
      for (int d = 0; d < sr.maxDoc(); d++) {
        if (live.isLive(d)) {
          totalLive++;
          totalTokens += lengths[d];
        }
      }
    }

    this.totalDocs = totalLive;
    this.avgDocLength = (totalLive > 0) ? ((float) totalTokens / (float) totalLive) : 0.0f;
  }

  public static IndexReader open(Path dir) throws IOException {
    SegmentInfos infos = SegmentInfos.readLatest(dir);
    List<SegmentReader> readers = new ArrayList<>();
    try {
      for (SegmentCommitInfo info : infos.segments()) {
        readers.add(new SegmentReader(dir, info.segmentName(), info.delGen()));
      }
      return new IndexReader(readers, infos.generation());
    } catch (Exception e) {
      for (SegmentReader r : readers) {
        try {
          r.close();
        } catch (Exception ex) {
          // Best-effort closing on initialization failure
        }
      }
      throw e;
    }
  }

  public List<SegmentReader> segmentReaders() {
    return Collections.unmodifiableList(segmentReaders);
  }

  public long generation() {
    return generation;
  }

  public int totalDocs() {
    return totalDocs;
  }

  public float avgDocLength() {
    return avgDocLength;
  }

  public Document document(int segmentIndex, int docId) throws IOException {
    return segmentReaders.get(segmentIndex).document(docId);
  }

  /** Computes the collection-wide document frequency for the given term across all segments. */
  public int docFreq(String term) throws IOException {
    int df = 0;
    for (SegmentReader sr : segmentReaders) {
      TermEntry entry = sr.dictReader().lookup(term);
      if (entry != null) {
        df += entry.df();
      }
    }
    return df;
  }

  public void incRef() {
    refCount.incrementAndGet();
  }

  public void decRef() throws IOException {
    if (refCount.decrementAndGet() == 0) {
      closeInternal();
    }
  }

  public int getRefCount() {
    return refCount.get();
  }

  @Override
  public void close() throws IOException {
    decRef();
  }

  private synchronized void closeInternal() throws IOException {
    if (!closed) {
      closed = true;
      for (SegmentReader r : segmentReaders) {
        r.close();
      }
    }
  }
}

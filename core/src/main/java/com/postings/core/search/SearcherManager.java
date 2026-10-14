package com.postings.core.search;

import com.postings.core.analysis.Analyzer;
import com.postings.core.analysis.DefaultAnalyzer;
import com.postings.core.index.IndexReader;
import com.postings.core.index.SegmentInfos;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages concurrent point-in-time searchers, refcounted acquisition/release, and non-blocking
 * refresh on new commits.
 */
public final class SearcherManager implements AutoCloseable {

  private final Path dir;
  private final Analyzer analyzer;
  private final BM25Params params;
  private final AtomicReference<IndexReader> currentReader;
  private volatile boolean closed = false;

  public SearcherManager(Path dir, Analyzer analyzer, BM25Params params) throws IOException {
    this.dir = dir;
    this.analyzer = analyzer;
    this.params = params;
    this.currentReader = new AtomicReference<>(IndexReader.open(dir));
  }

  public SearcherManager(Path dir) throws IOException {
    this(dir, DefaultAnalyzer.INSTANCE, BM25Params.DEFAULT);
  }

  /**
   * Acquires the current IndexSearcher snapshot. Increments reader refcount. Callers must invoke
   * {@link #release(IndexSearcher)} when finished.
   */
  public IndexSearcher acquire() throws IOException {
    ensureOpen();
    while (true) {
      IndexReader reader = currentReader.get();
      if (reader.getRefCount() <= 0) {
        continue;
      }
      reader.incRef();
      return new IndexSearcher(reader, analyzer, params);
    }
  }

  /** Releases an acquired searcher, decrementing the snapshot refcount. */
  public void release(IndexSearcher searcher) throws IOException {
    if (searcher != null) {
      searcher.reader().decRef();
    }
  }

  /**
   * Checks if a new commit point is available on disk, and opens a new reader snapshot if
   * generation has increased.
   *
   * @return true if reader was refreshed to a newer commit
   */
  public synchronized boolean maybeRefresh() throws IOException {
    ensureOpen();
    SegmentInfos latest = SegmentInfos.readLatest(dir);
    IndexReader oldReader = currentReader.get();

    if (latest.generation() > oldReader.generation()) {
      IndexReader newReader = IndexReader.open(dir);
      currentReader.set(newReader);
      oldReader.decRef(); // drops manager's hold on old reader
      return true;
    }
    return false;
  }

  private void ensureOpen() {
    if (closed) {
      throw new IllegalStateException("SearcherManager is closed");
    }
  }

  @Override
  public synchronized void close() throws IOException {
    if (!closed) {
      closed = true;
      IndexReader r = currentReader.get();
      if (r != null) {
        r.decRef();
      }
    }
  }
}

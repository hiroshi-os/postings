package com.postings.core.index;

import com.postings.core.codec.CodecUtil;
import com.postings.core.model.Document;
import com.postings.core.search.PostingsIterator;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reader for an immutable segment. Manages open file channels, verifies CRCs on open, and maintains
 * a refcount for safe background deletion.
 */
public final class SegmentReader implements AutoCloseable {

  private final Path dir;
  private final String segmentName;
  private final int maxDoc;
  private final int[] docLengths;
  private final StoredFieldsWriter.Reader storedReader;
  private final DictionaryReader dictReader;
  private final FileChannel postChannel;
  private final LiveDocs liveDocs;
  private final int delGen;

  private final AtomicInteger refCount = new AtomicInteger(1);
  private volatile boolean closed = false;

  public SegmentReader(Path dir, String segmentName, int delGen) throws IOException {
    this.dir = dir;
    this.segmentName = segmentName;
    this.delGen = delGen;

    Path lenFile = dir.resolve(segmentName + ".len");
    Path storedFile = dir.resolve(segmentName + ".stored");
    Path postFile = dir.resolve(segmentName + ".post");
    Path dictFile = dir.resolve(segmentName + ".dict");

    // Verify .post footer CRC32
    try (FileChannel fc = FileChannel.open(postFile, StandardOpenOption.READ)) {
      CodecUtil.verifyFooter(fc);
    }

    this.docLengths = DocLengthsWriter.read(lenFile);
    this.maxDoc = docLengths.length;

    this.storedReader = new StoredFieldsWriter.Reader(storedFile);
    this.dictReader = new DictionaryReader(dictFile);
    this.postChannel = FileChannel.open(postFile, StandardOpenOption.READ);

    // Live docs (.del)
    if (delGen > 0) {
      Path delFile = dir.resolve(segmentName + "_" + delGen + ".del");
      if (Files.exists(delFile)) {
        this.liveDocs = LiveDocs.read(delFile);
      } else {
        this.liveDocs = new LiveDocs(maxDoc);
      }
    } else {
      this.liveDocs = new LiveDocs(maxDoc);
    }
  }

  public SegmentReader(Path dir, String segmentName) throws IOException {
    this(dir, segmentName, 0);
  }

  public Path directory() {
    return dir;
  }

  public String segmentName() {
    return segmentName;
  }

  public int delGen() {
    return delGen;
  }

  public int maxDoc() {
    return maxDoc;
  }

  public int numDocs() {
    return liveDocs.liveCount();
  }

  public int docLength(int docId) {
    return docLengths[docId];
  }

  public int[] docLengths() {
    return docLengths;
  }

  public Document document(int docId) throws IOException {
    return storedReader.get(docId);
  }

  public LiveDocs liveDocs() {
    return liveDocs;
  }

  public DictionaryReader dictReader() {
    return dictReader;
  }

  /** Returns a PostingsIterator for the given term, or null if the term is absent. */
  public PostingsIterator postings(String term) throws IOException {
    return postings(term, 1.0f);
  }

  /**
   * Returns a PostingsIterator for the given term scaled by query-time IDF, or null if the term is
   * absent.
   */
  public PostingsIterator postings(String term, float idf) throws IOException {
    TermEntry entry = dictReader.lookup(term);
    if (entry == null) {
      return null;
    }
    return new SegmentPostingsIterator(
        postChannel, entry.postingsOffset(), entry.maxScore(), liveDocs, idf);
  }

  /** Increments ref count. */
  public void incRef() {
    refCount.incrementAndGet();
  }

  /** Decrements ref count and closes resources when it reaches zero. */
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
      try {
        storedReader.close();
      } finally {
        try {
          dictReader.close();
        } finally {
          postChannel.close();
        }
      }
    }
  }
}

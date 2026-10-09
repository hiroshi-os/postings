package com.postings.core.index;

import com.postings.core.analysis.Analyzer;
import com.postings.core.analysis.DefaultAnalyzer;
import com.postings.core.model.Document;
import com.postings.core.search.BM25Params;
import com.postings.core.search.BM25Scorer;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single-writer coordinator for creating and updating an inverted index. Enforces single-process
 * writer locking, RAM-budget flushing, immutable segment creation, deletions via live-docs
 * generations, and crash-safe commit points.
 */
public final class IndexWriter implements AutoCloseable {

  private static final Pattern SEG_NAME_PATTERN = Pattern.compile("^_(\\d+)$");

  private final Path dir;
  private final Analyzer analyzer;
  private final BM25Scorer scorer;
  private final long ramBudgetBytes;

  private final FileChannel lockChannel;
  private final FileLock fileLock;

  private final SegmentInfos segmentInfos;
  private final MemoryIndexBuffer ramBuffer;
  private int segmentCounter = 0;

  // Track (segmentName -> LiveDocs) for segments with pending deletions
  private final Map<String, LiveDocs> activeLiveDocs = new HashMap<>();

  // Location index: id -> DocLocation (segmentName, docId)
  public record DocLocation(String segmentName, int docId) {}

  private final Map<String, DocLocation> idLocations = new HashMap<>();

  public IndexWriter(Path dir, Analyzer analyzer, BM25Params bm25Params, long ramBudgetBytes)
      throws IOException {
    this.dir = dir;
    this.analyzer = analyzer;
    this.scorer = new BM25Scorer(bm25Params);
    this.ramBudgetBytes = ramBudgetBytes;

    Files.createDirectories(dir);

    // Acquire writer lock
    Path lockFile = dir.resolve("write.lock");
    this.lockChannel =
        FileChannel.open(
            lockFile, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
    FileLock lock;
    try {
      lock = lockChannel.tryLock();
    } catch (java.nio.channels.OverlappingFileLockException e) {
      lockChannel.close();
      throw new LockObtainFailedException(
          "Index directory is already locked by another writer: " + dir);
    }
    if (lock == null) {
      lockChannel.close();
      throw new LockObtainFailedException(
          "Index directory is already locked by another writer: " + dir);
    }
    this.fileLock = lock;

    this.segmentInfos = SegmentInfos.readLatest(dir);
    this.ramBuffer = new MemoryIndexBuffer(analyzer);

    // Discover maximum segment ID and populate id locations
    for (SegmentCommitInfo info : segmentInfos.segments()) {
      Matcher m = SEG_NAME_PATTERN.matcher(info.segmentName());
      if (m.matches()) {
        int id = Integer.parseInt(m.group(1));
        if (id >= segmentCounter) {
          segmentCounter = id + 1;
        }
      }

      // Load live docs for segment
      if (info.delGen() > 0) {
        Path delPath = dir.resolve(info.segmentName() + "_" + info.delGen() + ".del");
        if (Files.exists(delPath)) {
          activeLiveDocs.put(info.segmentName(), LiveDocs.read(delPath));
        } else {
          activeLiveDocs.put(info.segmentName(), new LiveDocs(info.docCount()));
        }
      } else {
        activeLiveDocs.put(info.segmentName(), new LiveDocs(info.docCount()));
      }

      // Load doc IDs from .stored
      Path storedPath = dir.resolve(info.segmentName() + ".stored");
      if (Files.exists(storedPath)) {
        try (StoredFieldsWriter.Reader storedReader = new StoredFieldsWriter.Reader(storedPath)) {
          LiveDocs ld = activeLiveDocs.get(info.segmentName());
          for (int d = 0; d < storedReader.numDocs(); d++) {
            if (ld.isLive(d)) {
              Document doc = storedReader.get(d);
              idLocations.put(doc.id(), new DocLocation(info.segmentName(), d));
            }
          }
        }
      }
    }
  }

  public Analyzer analyzer() {
    return analyzer;
  }

  public IndexWriter(Path dir) throws IOException {
    this(dir, DefaultAnalyzer.INSTANCE, BM25Params.DEFAULT, 256L * 1024 * 1024);
  }

  /**
   * Adds a document to the indexing buffer. If a document with the same id exists, it is marked
   * deleted.
   */
  public synchronized void addDocument(Document doc) throws IOException {
    // Delete existing document if present
    deleteById(doc.id());

    ramBuffer.addDocument(doc);
    if (ramBuffer.estimatedRamBytes() >= ramBudgetBytes) {
      flush();
    }
  }

  /**
   * Deletes a document by external id. Marks a bit in the active live-docs of the target segment.
   */
  public synchronized void deleteById(String id) {
    DocLocation loc = idLocations.remove(id);
    if (loc != null) {
      LiveDocs ld = activeLiveDocs.get(loc.segmentName());
      if (ld != null) {
        ld.delete(loc.docId());
      }
    }
  }

  /** Flushes the current RAM buffer into a new segment. */
  public synchronized void flush() throws IOException {
    if (ramBuffer.numDocs() == 0) {
      return;
    }

    String segName = "_" + segmentCounter++;
    SegmentWriter.writeSegment(dir, segName, ramBuffer, scorer);

    int docCount = ramBuffer.numDocs();
    SegmentCommitInfo info = new SegmentCommitInfo(segName, docCount, 0, 0);
    segmentInfos.add(info);
    activeLiveDocs.put(segName, new LiveDocs(docCount));

    List<String> docIds = ramBuffer.docIds();
    for (int d = 0; d < docIds.size(); d++) {
      idLocations.put(docIds.get(d), new DocLocation(segName, d));
    }

    ramBuffer.clear();
  }

  /**
   * Commits all changes durably to disk: 1. Flushes RAM buffer 2. Writes new .del generations for
   * segments with deletions and fsyncs them 3. Writes segments_N atomically 4. Deletes unreferenced
   * files
   */
  public synchronized long commit() throws IOException {
    flush();

    // Check for updated deletions
    List<SegmentCommitInfo> updatedSegments = new ArrayList<>();
    for (SegmentCommitInfo info : segmentInfos.segments()) {
      LiveDocs ld = activeLiveDocs.get(info.segmentName());
      if (ld != null && ld.hasDeletions() && ld.delCount() != info.delCount()) {
        int nextDelGen = info.delGen() + 1;
        Path delFile = dir.resolve(info.segmentName() + "_" + nextDelGen + ".del");
        LiveDocs.write(delFile, ld);
        fsync(delFile);
        updatedSegments.add(
            new SegmentCommitInfo(info.segmentName(), info.docCount(), nextDelGen, ld.delCount()));
      } else {
        updatedSegments.add(info);
      }
    }

    segmentInfos.setSegments(updatedSegments);
    segmentInfos.commit(dir);

    cleanupUnreferencedFiles();
    return segmentInfos.generation();
  }

  public synchronized SegmentInfos segmentInfos() {
    return segmentInfos;
  }

  private void cleanupUnreferencedFiles() {
    Set<String> referencedFiles = new HashSet<>();
    referencedFiles.add("write.lock");
    referencedFiles.add("segments_" + segmentInfos.generation());

    for (SegmentCommitInfo info : segmentInfos.segments()) {
      referencedFiles.add(info.segmentName() + ".len");
      referencedFiles.add(info.segmentName() + ".stored");
      referencedFiles.add(info.segmentName() + ".post");
      referencedFiles.add(info.segmentName() + ".dict");
      if (info.delGen() > 0) {
        referencedFiles.add(info.segmentName() + "_" + info.delGen() + ".del");
      }
    }

    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
      for (Path file : stream) {
        String name = file.getFileName().toString();
        if (!referencedFiles.contains(name) && !name.endsWith(".tmp")) {
          // If it's an old segment file or old segments_N, safe to delete
          try {
            Files.deleteIfExists(file);
          } catch (Exception e) {
            // Best-effort deletion of old unreferenced files
          }
        }
      }
    } catch (IOException ignored) {
      // Best-effort directory cleanup scan
    }
  }

  private static void fsync(Path file) throws IOException {
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
      channel.force(true);
    }
  }

  @Override
  public synchronized void close() throws IOException {
    try {
      commit();
    } finally {
      try {
        fileLock.release();
      } finally {
        lockChannel.close();
      }
    }
  }
}

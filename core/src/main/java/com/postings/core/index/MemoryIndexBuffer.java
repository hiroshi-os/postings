package com.postings.core.index;

import com.postings.core.analysis.Analyzer;
import com.postings.core.analysis.DefaultAnalyzer;
import com.postings.core.model.Document;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * In-memory indexing buffer that accumulates documents before flushing to a segment.
 *
 * <p>Uses a term -&gt; growable int-array postings map and tracks document lengths and RAM usage.
 */
public final class MemoryIndexBuffer {

  private final Analyzer analyzer;
  private final TreeMap<String, PostingListBuilder> terms;
  private final List<String> docIds;
  private final List<String> docTitles;
  private int[] docLengths;
  private int numDocs;
  private long estimatedRamBytes;

  public static final class PostingListBuilder {
    int[] docIds = new int[8];
    int[] freqs = new int[8];
    int size = 0;

    void add(int docId, int freq) {
      if (size == docIds.length) {
        int newCap = docIds.length * 2;
        int[] newDocIds = new int[newCap];
        int[] newFreqs = new int[newCap];
        System.arraycopy(docIds, 0, newDocIds, 0, size);
        System.arraycopy(freqs, 0, newFreqs, 0, size);
        docIds = newDocIds;
        freqs = newFreqs;
      }
      docIds[size] = docId;
      freqs[size] = freq;
      size++;
    }

    public int size() {
      return size;
    }

    public int[] docIds() {
      return docIds;
    }

    public int[] freqs() {
      return freqs;
    }
  }

  public MemoryIndexBuffer(Analyzer analyzer) {
    this.analyzer = analyzer;
    this.terms = new TreeMap<>();
    this.docIds = new ArrayList<>();
    this.docTitles = new ArrayList<>();
    this.docLengths = new int[16];
    this.numDocs = 0;
    this.estimatedRamBytes = 0;
  }

  public MemoryIndexBuffer() {
    this(DefaultAnalyzer.INSTANCE);
  }

  /**
   * Adds a document to the buffer.
   *
   * @param doc the document to index
   * @return the assigned dense segment docId
   */
  public synchronized int addDocument(Document doc) {
    int docId = numDocs++;
    if (docId >= docLengths.length) {
      int newCap = Math.max(docLengths.length * 2, docId + 1);
      int[] newLens = new int[newCap];
      System.arraycopy(docLengths, 0, newLens, 0, docLengths.length);
      docLengths = newLens;
    }

    String fullText;
    if (doc.title().isEmpty()) {
      fullText = doc.body();
    } else {
      fullText = doc.title() + " " + doc.body();
    }

    List<String> tokens = analyzer.analyze(fullText);
    int docLen = tokens.size();
    docLengths[docId] = docLen;
    docIds.add(doc.id());
    docTitles.add(doc.title());

    estimatedRamBytes += 16 + (doc.id().length() * 2L) + (doc.title().length() * 2L) + 4;

    // Count term frequencies
    Map<String, Integer> termFreqs = new HashMap<>();
    for (String token : tokens) {
      termFreqs.merge(token, 1, Integer::sum);
    }

    for (Map.Entry<String, Integer> entry : termFreqs.entrySet()) {
      String term = entry.getKey();
      int freq = entry.getValue();

      PostingListBuilder list = terms.get(term);
      if (list == null) {
        list = new PostingListBuilder();
        terms.put(term, list);
        estimatedRamBytes += 48 + (term.length() * 2L) + 64; // approx overhead
      }
      list.add(docId, freq);
      estimatedRamBytes += 8;
    }

    return docId;
  }

  public synchronized int numDocs() {
    return numDocs;
  }

  public synchronized int docLength(int docId) {
    if (docId < 0 || docId >= numDocs) {
      throw new IndexOutOfBoundsException("docId " + docId + " out of bounds");
    }
    return docLengths[docId];
  }

  public synchronized String externalId(int docId) {
    return docIds.get(docId);
  }

  public synchronized String title(int docId) {
    return docTitles.get(docId);
  }

  public synchronized List<String> docIds() {
    return Collections.unmodifiableList(docIds);
  }

  public synchronized List<String> docTitles() {
    return Collections.unmodifiableList(docTitles);
  }

  public synchronized int[] docLengths() {
    int[] copy = new int[numDocs];
    System.arraycopy(docLengths, 0, copy, 0, numDocs);
    return copy;
  }

  public synchronized java.util.NavigableMap<String, PostingListBuilder> terms() {
    return terms;
  }

  public synchronized long estimatedRamBytes() {
    return estimatedRamBytes;
  }

  public synchronized void clear() {
    terms.clear();
    docIds.clear();
    docTitles.clear();
    docLengths = new int[16];
    numDocs = 0;
    estimatedRamBytes = 0;
  }
}

package com.postings.core.search;

import com.postings.core.analysis.Analyzer;
import com.postings.core.analysis.DefaultAnalyzer;
import com.postings.core.index.IndexReader;
import com.postings.core.index.SegmentReader;
import com.postings.core.model.Document;
import com.postings.core.model.ScoredDoc;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/** Executes queries against a point-in-time IndexReader snapshot. */
public final class IndexSearcher {

  private final IndexReader reader;
  private final Analyzer analyzer;
  private final BM25Params params;
  private final BM25Scorer scorer;

  public IndexSearcher(IndexReader reader, Analyzer analyzer, BM25Params params) {
    this.reader = reader;
    this.analyzer = analyzer;
    this.params = params;
    this.scorer = new BM25Scorer(params);
  }

  public IndexSearcher(IndexReader reader) {
    this(reader, DefaultAnalyzer.INSTANCE, BM25Params.DEFAULT);
  }

  public IndexReader reader() {
    return reader;
  }

  public BM25Params params() {
    return params;
  }

  public Document doc(int segmentIndex, int docId) throws IOException {
    return reader.document(segmentIndex, docId);
  }

  /** Searches the index and returns top-k hits. */
  public SearchResult search(String query, int k, QueryMode mode, Algo algo) throws IOException {
    long startNanos = System.nanoTime();
    int totalDocs = reader.totalDocs();
    if (totalDocs == 0 || k <= 0) {
      long took = (System.nanoTime() - startNanos) / 1000;
      return new SearchResult(List.of(), took, 0, 0);
    }

    List<String> queryTokens = analyzer.analyze(query);
    if (queryTokens.isEmpty()) {
      long took = (System.nanoTime() - startNanos) / 1000;
      return new SearchResult(List.of(), took, 0, 0);
    }

    Set<String> uniqueTerms = new HashSet<>(queryTokens);

    // Compute collection-wide IDF for each term
    float[] idfs = new float[uniqueTerms.size()];
    String[] terms = uniqueTerms.toArray(new String[0]);
    for (int t = 0; t < terms.length; t++) {
      int df = reader.docFreq(terms[t]);
      idfs[t] = BM25Scorer.idf(totalDocs, df);
    }

    // Min-heap for top-k
    PriorityQueue<ScoredDoc> minHeap = new PriorityQueue<>(k, ScoredDoc.MIN_HEAP_COMPARATOR);
    long totalDocsScored = 0;
    long totalBlocksSkipped = 0;

    List<SegmentReader> segReaders = reader.segmentReaders();
    for (int segIdx = 0; segIdx < segReaders.size(); segIdx++) {
      SegmentReader sr = segReaders.get(segIdx);
      if (sr.numDocs() == 0) {
        continue;
      }

      // Collect postings iterators for this segment
      List<PostingsIterator> iterators = new ArrayList<>();
      List<Float> termIdfs = new ArrayList<>();
      for (int t = 0; t < terms.length; t++) {
        PostingsIterator it = sr.postings(terms[t]);
        if (it != null) {
          iterators.add(it);
          termIdfs.add(idfs[t]);
        }
      }

      if (iterators.isEmpty()) {
        continue;
      }

      if (mode == QueryMode.AND) {
        if (iterators.size() < terms.length) {
          // If any term is absent in this segment, AND has 0 matches
          continue;
        }
        totalDocsScored += searchAnd(segIdx, sr, iterators, termIdfs, k, minHeap);
      } else {
        // Disjunction (OR) mode
        switch (algo) {
          case EXHAUSTIVE ->
              totalDocsScored += searchOrExhaustive(segIdx, sr, iterators, termIdfs, k, minHeap);
          case WAND -> {
            long[] stats = searchOrWand(segIdx, sr, iterators, termIdfs, k, minHeap);
            totalDocsScored += stats[0];
            totalBlocksSkipped += stats[1];
          }
          case BMW -> {
            long[] stats = searchOrBmw(segIdx, sr, iterators, termIdfs, k, minHeap);
            totalDocsScored += stats[0];
            totalBlocksSkipped += stats[1];
          }
        }
      }
    }

    List<ScoredDoc> hits = new ArrayList<>(minHeap.size());
    while (!minHeap.isEmpty()) {
      hits.add(minHeap.poll());
    }
    hits.sort(ScoredDoc.RESULT_ORDER);

    long took = (System.nanoTime() - startNanos) / 1000;
    return new SearchResult(hits, took, totalDocsScored, totalBlocksSkipped);
  }

  private long searchAnd(
      int segIdx,
      SegmentReader sr,
      List<PostingsIterator> iterators,
      List<Float> idfs,
      int k,
      PriorityQueue<ScoredDoc> minHeap)
      throws IOException {
    long scoredCount = 0;
    float avgdl = reader.avgDocLength();

    // Sort iterators by leading iterator
    PostingsIterator lead = iterators.get(0);
    int target = lead.nextDoc();

    outer:
    while (target != PostingsIterator.NO_MORE_DOCS) {
      for (int i = 1; i < iterators.size(); i++) {
        PostingsIterator it = iterators.get(i);
        if (it.docId() < target) {
          int doc = it.advance(target);
          if (doc == PostingsIterator.NO_MORE_DOCS) {
            break outer;
          }
          if (doc > target) {
            target = lead.advance(doc);
            continue outer;
          }
        }
      }

      // All iterators aligned at 'target'
      scoredCount++;
      int docLen = sr.docLength(target);
      float score = 0.0f;
      for (int i = 0; i < iterators.size(); i++) {
        score += scorer.score(iterators.get(i).freq(), docLen, avgdl, idfs.get(i));
      }

      collectHit(segIdx, target, score, sr, k, minHeap);
      target = lead.nextDoc();
    }

    return scoredCount;
  }

  private long searchOrExhaustive(
      int segIdx,
      SegmentReader sr,
      List<PostingsIterator> iterators,
      List<Float> idfs,
      int k,
      PriorityQueue<ScoredDoc> minHeap)
      throws IOException {
    long scoredCount = 0;
    float avgdl = reader.avgDocLength();

    // Prime iterators
    for (PostingsIterator it : iterators) {
      it.nextDoc();
    }

    while (true) {
      int minDoc = Integer.MAX_VALUE;
      for (PostingsIterator it : iterators) {
        if (it.docId() < minDoc) {
          minDoc = it.docId();
        }
      }

      if (minDoc == PostingsIterator.NO_MORE_DOCS) {
        break;
      }

      scoredCount++;
      int docLen = sr.docLength(minDoc);
      float score = 0.0f;

      for (int i = 0; i < iterators.size(); i++) {
        PostingsIterator it = iterators.get(i);
        if (it.docId() == minDoc) {
          score += scorer.score(it.freq(), docLen, avgdl, idfs.get(i));
          it.nextDoc();
        }
      }

      if (score > 0.0f) {
        collectHit(segIdx, minDoc, score, sr, k, minHeap);
      }
    }

    return scoredCount;
  }

  private long[] searchOrWand(
      int segIdx,
      SegmentReader sr,
      List<PostingsIterator> iterators,
      List<Float> idfs,
      int k,
      PriorityQueue<ScoredDoc> minHeap)
      throws IOException {
    // For M3, execute exhaustive and track stats; refined to full WAND in M4
    long scored = searchOrExhaustive(segIdx, sr, iterators, idfs, k, minHeap);
    return new long[] {scored, 0};
  }

  private long[] searchOrBmw(
      int segIdx,
      SegmentReader sr,
      List<PostingsIterator> iterators,
      List<Float> idfs,
      int k,
      PriorityQueue<ScoredDoc> minHeap)
      throws IOException {
    // For M3, execute exhaustive and track stats; refined to full BMW in M4
    long scored = searchOrExhaustive(segIdx, sr, iterators, idfs, k, minHeap);
    return new long[] {scored, 0};
  }

  private void collectHit(
      int segIdx, int docId, float score, SegmentReader sr, int k, PriorityQueue<ScoredDoc> minHeap)
      throws IOException {
    if (minHeap.size() < k) {
      Document doc = sr.document(docId);
      minHeap.offer(new ScoredDoc(segIdx, docId, doc.id(), score, doc.title()));
    } else {
      ScoredDoc min = minHeap.peek();
      ScoredDoc candidate = new ScoredDoc(segIdx, docId, "", score, "");
      if (ScoredDoc.MIN_HEAP_COMPARATOR.compare(candidate, min) > 0) {
        Document doc = sr.document(docId);
        minHeap.poll();
        minHeap.offer(new ScoredDoc(segIdx, docId, doc.id(), score, doc.title()));
      }
    }
  }
}

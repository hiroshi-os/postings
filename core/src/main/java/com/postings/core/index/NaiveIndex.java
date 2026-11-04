package com.postings.core.index;

import com.postings.core.analysis.Analyzer;
import com.postings.core.analysis.DefaultAnalyzer;
import com.postings.core.model.Document;
import com.postings.core.model.ScoredDoc;
import com.postings.core.search.BM25Params;
import com.postings.core.search.BM25Scorer;
import com.postings.core.search.QueryMode;
import com.postings.core.search.SearchResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Naive in-memory index used as the ground truth reference model in tests.
 *
 * <p>Stores live documents and term occurrences, evaluating queries with exhaustive BM25 scoring.
 */
public final class NaiveIndex {

  private record StoredDoc(
      int docId, String id, String title, int length, Map<String, Integer> termFreqs) {}

  private final Analyzer analyzer;
  private final BM25Scorer scorer;
  private final Map<String, StoredDoc> docs = new LinkedHashMap<>();
  private final Set<String> deletedIds = new HashSet<>();
  private int nextDocId = 0;

  public NaiveIndex(Analyzer analyzer, BM25Params params) {
    this.analyzer = analyzer;
    this.scorer = new BM25Scorer(params);
  }

  public NaiveIndex() {
    this(DefaultAnalyzer.INSTANCE, BM25Params.DEFAULT);
  }

  public synchronized void addDocument(Document doc) {
    deletedIds.remove(doc.id());

    String text = doc.title().isEmpty() ? doc.body() : doc.title() + " " + doc.body();
    List<String> tokens = analyzer.analyze(text);
    Map<String, Integer> freqs = new HashMap<>();
    for (String token : tokens) {
      freqs.merge(token, 1, Integer::sum);
    }

    StoredDoc stored = new StoredDoc(nextDocId++, doc.id(), doc.title(), tokens.size(), freqs);
    docs.put(doc.id(), stored);
  }

  public synchronized void deleteById(String id) {
    if (docs.containsKey(id)) {
      deletedIds.add(id);
    }
  }

  public synchronized int liveDocCount() {
    return docs.size() - deletedIds.size();
  }

  public synchronized SearchResult search(String query, int k, QueryMode mode) {
    long startNanos = System.nanoTime();
    List<StoredDoc> liveDocs = new ArrayList<>();
    long totalLen = 0;
    for (Map.Entry<String, StoredDoc> entry : docs.entrySet()) {
      if (!deletedIds.contains(entry.getKey())) {
        liveDocs.add(entry.getValue());
        totalLen += entry.getValue().length();
      }
    }

    int n = liveDocs.size();
    if (n == 0 || k <= 0) {
      long took = (System.nanoTime() - startNanos) / 1000;
      return new SearchResult(List.of(), took, 0, 0);
    }

    float avgdl = (float) totalLen / (float) n;
    List<String> queryTokens = analyzer.analyze(query);
    if (queryTokens.isEmpty()) {
      long took = (System.nanoTime() - startNanos) / 1000;
      return new SearchResult(List.of(), took, 0, 0);
    }

    // Unique query terms
    Set<String> uniqueTerms = new HashSet<>(queryTokens);

    // Compute df and idf for each query term across live docs
    Map<String, Float> idfMap = new HashMap<>();
    for (String term : uniqueTerms) {
      long df = 0;
      for (StoredDoc d : liveDocs) {
        if (d.termFreqs().containsKey(term)) {
          df++;
        }
      }
      idfMap.put(term, BM25Scorer.idf(n, df));
    }

    List<ScoredDoc> scored = new ArrayList<>();
    long docsScored = 0;

    for (StoredDoc doc : liveDocs) {
      boolean hasAll = true;
      boolean hasAny = false;
      float docScore = 0.0f;

      for (String term : uniqueTerms) {
        int tf = doc.termFreqs().getOrDefault(term, 0);
        if (tf > 0) {
          hasAny = true;
          float idf = idfMap.get(term);
          docScore += scorer.score(tf, doc.length(), avgdl, idf);
        } else {
          hasAll = false;
        }
      }

      boolean matches = (mode == QueryMode.AND) ? hasAll : hasAny;
      if (matches && docScore > 0.0f) {
        docsScored++;
        scored.add(new ScoredDoc(0, doc.docId(), doc.id(), docScore, doc.title()));
      }
    }

    scored.sort(ScoredDoc.RESULT_ORDER);
    List<ScoredDoc> topHits = scored.subList(0, Math.min(k, scored.size()));
    long took = (System.nanoTime() - startNanos) / 1000;
    return new SearchResult(topHits, took, docsScored, 0);
  }
}

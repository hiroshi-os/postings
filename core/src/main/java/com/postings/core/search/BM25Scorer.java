package com.postings.core.search;

/**
 * Computes BM25 Okapi term and document scores matching Lucene / Anserini defaults.
 *
 * <p>Formula:
 *
 * <pre>
 *   IDF = ln(1 + (N - df + 0.5) / (df + 0.5))
 *   Score = IDF * (tf * (k1 + 1)) / (tf + k1 * (1 - b + b * (docLen / avgdl)))
 * </pre>
 */
public final class BM25Scorer {

  private final BM25Params params;

  public BM25Scorer(BM25Params params) {
    this.params = params;
  }

  public BM25Scorer() {
    this(BM25Params.DEFAULT);
  }

  public BM25Params params() {
    return params;
  }

  /** Computes Lucene's BM25 IDF: ln(1 + (N - df + 0.5) / (df + 0.5)). */
  public static float idf(long totalDocs, long docFreq) {
    if (totalDocs <= 0 || docFreq <= 0) {
      return 0.0f;
    }
    double n = (double) totalDocs;
    double df = (double) docFreq;
    double val = Math.log(1.0 + (n - df + 0.5) / (df + 0.5));
    return (float) Math.max(0.0, val);
  }

  /**
   * Computes the BM25 score for a single term in a document.
   *
   * @param tf Term frequency in the document
   * @param docLen Document length in tokens
   * @param avgDocLen Average document length across the collection
   * @param idf Inverse document frequency of the term
   * @return Term BM25 score
   */
  public float score(int tf, int docLen, float avgDocLen, float idf) {
    if (tf <= 0 || idf <= 0.0f) {
      return 0.0f;
    }
    float k1 = params.k1();
    float b = params.b();
    float norm = (avgDocLen > 0.0f) ? ((float) docLen / avgDocLen) : 1.0f;
    float numerator = tf * (k1 + 1.0f);
    float denominator = tf + k1 * (1.0f - b + b * norm);
    return idf * (numerator / denominator);
  }

  /**
   * Computes the maximum possible BM25 score for a term given the maximum term frequency and
   * minimum document length in a block or segment.
   */
  public float maxScore(int maxTf, int minDocLen, float avgDocLen, float idf) {
    return score(maxTf, minDocLen, avgDocLen, idf);
  }
}

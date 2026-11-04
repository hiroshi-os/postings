package com.postings.core.search;

/**
 * Hyperparameters for BM25 ranking.
 *
 * @param k1 Term frequency saturation parameter (default 0.9)
 * @param b Document length normalization parameter (default 0.4)
 */
public record BM25Params(float k1, float b) {
  public static final BM25Params DEFAULT = new BM25Params(0.9f, 0.4f);

  public BM25Params {
    if (k1 < 0.0f) {
      throw new IllegalArgumentException("k1 must be non-negative: " + k1);
    }
    if (b < 0.0f || b > 1.0f) {
      throw new IllegalArgumentException("b must be in [0, 1]: " + b);
    }
  }
}

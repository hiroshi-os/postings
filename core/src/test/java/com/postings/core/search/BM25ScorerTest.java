package com.postings.core.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class BM25ScorerTest {

  private final BM25Scorer scorer = new BM25Scorer(new BM25Params(0.9f, 0.4f));

  @Test
  void testHandComputedValues() {
    long n = 1000;
    long df = 10;
    float idf = BM25Scorer.idf(n, df);
    // IDF = ln(1 + (1000 - 10 + 0.5) / (10 + 0.5)) = ln(1 + 990.5 / 10.5) ~ 4.5573795
    double expectedIdf = Math.log(1.0 + 990.5 / 10.5);
    assertThat(idf).isCloseTo((float) expectedIdf, within(1e-5f));

    int tf = 2;
    int docLen = 50;
    float avgdl = 100.0f;
    float score = scorer.score(tf, docLen, avgdl, idf);

    // norm = 50 / 100 = 0.5
    // num = 2 * (0.9 + 1) = 3.8
    // den = 2 + 0.9 * (1 - 0.4 + 0.4 * 0.5) = 2 + 0.9 * 0.8 = 2.72
    // score = expectedIdf * (3.8 / 2.72)
    float expectedScore = (float) (expectedIdf * (3.8 / 2.72));
    assertThat(score).isCloseTo(expectedScore, within(1e-5f));
  }

  @Test
  void testZeroTfReturnsZero() {
    float idf = BM25Scorer.idf(100, 10);
    assertThat(scorer.score(0, 50, 50.0f, idf)).isEqualTo(0.0f);
  }

  @Test
  void testMonotonicity() {
    float idf = BM25Scorer.idf(1000, 50);
    float avgdl = 80.0f;

    // Higher tf -> higher score
    float s1 = scorer.score(1, 80, avgdl, idf);
    float s2 = scorer.score(2, 80, avgdl, idf);
    float s3 = scorer.score(5, 80, avgdl, idf);
    assertThat(s1).isLessThan(s2);
    assertThat(s2).isLessThan(s3);

    // Longer doc -> lower score for identical tf
    float sShort = scorer.score(2, 40, avgdl, idf);
    float sLong = scorer.score(2, 200, avgdl, idf);
    assertThat(sShort).isGreaterThan(sLong);
  }
}

package com.postings.core.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.postings.core.index.IndexReader;
import com.postings.core.index.IndexWriter;
import com.postings.core.index.NaiveIndex;
import com.postings.core.model.Document;
import com.postings.core.model.ScoredDoc;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class HeadlineEquivalencePropertyTest {

  public static final AtomicLong TOTAL_TRIES = new AtomicLong(0);
  public static final AtomicLong TOTAL_BLOCKS_SKIPPED = new AtomicLong(0);

  @Property
  void testBmwWandExhaustiveEquivalence(@ForAll("seeds") long seed) throws Exception {
    TOTAL_TRIES.incrementAndGet();
    Random rng = new Random(seed);

    int numDocs = 40 + rng.nextInt(120);
    int vocabSize = 30 + rng.nextInt(70);
    int numSegments = 2 + rng.nextInt(3);

    Path tempDir = Files.createTempDirectory("headline_test_" + seed + "_");
    NaiveIndex naive = new NaiveIndex();

    try {
      try (IndexWriter writer = new IndexWriter(tempDir)) {
        int docsPerSegment = (numDocs + numSegments - 1) / numSegments;
        for (int i = 0; i < numDocs; i++) {
          String id = "doc_" + i;
          String body = generateZipfianText(rng, vocabSize, 10 + rng.nextInt(40));
          Document doc = new Document(id, "Title " + i, body);

          writer.addDocument(doc);
          naive.addDocument(doc);

          if ((i + 1) % docsPerSegment == 0) {
            writer.flush();
          }
        }

        // Randomly delete ~10% of documents
        for (int i = 0; i < numDocs; i++) {
          if (rng.nextFloat() < 0.10f) {
            String delId = "doc_" + i;
            writer.deleteById(delId);
            naive.deleteById(delId);
          }
        }

        writer.commit();
      }

      try (IndexReader reader = IndexReader.open(tempDir)) {
        IndexSearcher searcher = new IndexSearcher(reader);

        // Generate query of 1..5 terms
        int queryLen = 1 + rng.nextInt(5);
        List<String> queryTerms = new ArrayList<>();
        for (int q = 0; q < queryLen; q++) {
          queryTerms.add("term" + sampleZipf(rng, vocabSize));
        }
        String query = String.join(" ", queryTerms);

        int[] kValues = {1, 10, 100};
        for (int k : kValues) {
          SearchResult rExhaustive = searcher.search(query, k, QueryMode.OR, Algo.EXHAUSTIVE);
          SearchResult rWand = searcher.search(query, k, QueryMode.OR, Algo.WAND);
          SearchResult rBmw = searcher.search(query, k, QueryMode.OR, Algo.BMW);

          TOTAL_BLOCKS_SKIPPED.addAndGet(rBmw.blocksSkipped());

          if (!rWand.hits().equals(rExhaustive.hits())) {
            System.err.println("DEBUG query: " + query + " k: " + k);
            System.err.println("Exhaustive hits: " + rExhaustive.hits());
            System.err.println("WAND hits: " + rWand.hits());
            System.err.println("BMW hits: " + rBmw.hits());
          }

          // Verify exact equivalence of hit count
          assertThat(rWand.hits()).hasSameSizeAs(rExhaustive.hits());
          assertThat(rBmw.hits()).hasSameSizeAs(rExhaustive.hits());

          for (int h = 0; h < rExhaustive.hits().size(); h++) {
            ScoredDoc hEx = rExhaustive.hits().get(h);
            ScoredDoc hWand = rWand.hits().get(h);
            ScoredDoc hBmw = rBmw.hits().get(h);

            assertThat(hWand.id())
                .as("WAND vs Exhaustive ID mismatch at rank %d for seed %d", h, seed)
                .isEqualTo(hEx.id());
            assertThat(hBmw.id())
                .as("BMW vs Exhaustive ID mismatch at rank %d for seed %d", h, seed)
                .isEqualTo(hEx.id());

            assertThat(hWand.score())
                .as("WAND score mismatch at rank %d", h)
                .isCloseTo(hEx.score(), within(1e-4f));
            assertThat(hBmw.score())
                .as("BMW score mismatch at rank %d", h)
                .isCloseTo(hEx.score(), within(1e-4f));
          }
        }

        // Test AND mode vs naive model
        SearchResult rAnd = searcher.search(query, 10, QueryMode.AND, Algo.EXHAUSTIVE);
        SearchResult rNaiveAnd = naive.search(query, 10, QueryMode.AND);
        if (rAnd.hits().size() != rNaiveAnd.hits().size()) {
          System.err.printf(
              "AND SIZE MISMATCH: rAnd=%d vs rNaiveAnd=%d query='%s' totalDocs=%d naiveLive=%d%n",
              rAnd.hits().size(),
              rNaiveAnd.hits().size(),
              query,
              reader.totalDocs(),
              naive.liveDocCount());
          System.err.println("rNaiveAnd hits: " + rNaiveAnd.hits());
          for (String term : queryTerms) {
            System.err.printf("term '%s' reader df=%d%n", term, reader.docFreq(term));
          }
        }
        assertThat(rAnd.hits().size()).isEqualTo(rNaiveAnd.hits().size());
        for (int h = 0; h < rAnd.hits().size(); h++) {
          ScoredDoc hitAnd = rAnd.hits().get(h);
          ScoredDoc hitNaive = rNaiveAnd.hits().get(h);
          if (Math.abs(hitAnd.score() - hitNaive.score()) > 1e-4f) {
            System.err.printf(
                "MISMATCH at h=%d: hitAnd=%s vs hitNaive=%s query='%s' totalDocs=%d avgdl=%f naiveLive=%d%n",
                h,
                hitAnd,
                hitNaive,
                query,
                reader.totalDocs(),
                reader.avgDocLength(),
                naive.liveDocCount());
          }
          assertThat(hitAnd.score())
              .as("AND mode score mismatch at rank %d", h)
              .isCloseTo(hitNaive.score(), within(1e-4f));
        }
      }
    } finally {
      deleteDir(tempDir);
    }
  }

  private String generateZipfianText(Random rng, int vocabSize, int length) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < length; i++) {
      if (i > 0) {
        sb.append(' ');
      }
      sb.append("term").append(sampleZipf(rng, vocabSize));
    }
    return sb.toString();
  }

  private int sampleZipf(Random rng, int vocabSize) {
    // Rejection / approximation of Zipfian power law (s=1.0)
    double u = rng.nextDouble();
    // Cumulative approximation: F(x) = ln(x) / ln(N)
    int rank = (int) Math.floor(Math.pow(vocabSize, u));
    return Math.max(1, Math.min(vocabSize, rank));
  }

  private void deleteDir(Path dir) {
    try {
      if (Files.exists(dir)) {
        try (var stream = Files.walk(dir)) {
          stream.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
      }
    } catch (IOException ignored) {
      // Best-effort cleanup
    }
  }

  @Provide
  Arbitrary<Long> seeds() {
    return Arbitraries.longs();
  }
}

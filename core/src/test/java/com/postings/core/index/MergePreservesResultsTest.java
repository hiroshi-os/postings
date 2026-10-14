package com.postings.core.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.postings.core.model.Document;
import com.postings.core.model.ScoredDoc;
import com.postings.core.search.Algo;
import com.postings.core.search.IndexSearcher;
import com.postings.core.search.QueryMode;
import com.postings.core.search.SearchResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MergePreservesResultsTest {

  @Test
  void testForceMergePreservesTopK(@TempDir Path tempDir) throws Exception {
    Random rng = new Random(42);
    int numDocs = 300;
    int vocabSize = 50;

    List<String> docIds = new ArrayList<>();
    try (IndexWriter writer = new IndexWriter(tempDir)) {
      // Create ~5 segments
      for (int i = 0; i < numDocs; i++) {
        String id = "doc_" + i;
        docIds.add(id);
        String body = generateText(rng, vocabSize, 20);
        writer.addDocument(new Document(id, "Title " + i, body));
        if ((i + 1) % 60 == 0) {
          writer.flush();
        }
      }

      // Delete ~15% of docs
      for (int i = 0; i < numDocs; i++) {
        if (rng.nextFloat() < 0.15f) {
          writer.deleteById(docIds.get(i));
        }
      }

      writer.commit();
      assertThat(writer.segmentInfos().size()).isGreaterThanOrEqualTo(5);
    }

    List<String> queries =
        List.of(
            "term1 term2",
            "term5 term10 term15",
            "term3 term7",
            "term12 term24 term36",
            "term0 term1 term2 term3");

    List<SearchResult> preMergeResults = new ArrayList<>();
    try (IndexReader reader = IndexReader.open(tempDir)) {
      IndexSearcher searcher = new IndexSearcher(reader);
      for (String q : queries) {
        preMergeResults.add(searcher.search(q, 10, QueryMode.OR, Algo.EXHAUSTIVE));
        preMergeResults.add(searcher.search(q, 10, QueryMode.OR, Algo.BMW));
        preMergeResults.add(searcher.search(q, 10, QueryMode.AND, Algo.EXHAUSTIVE));
      }
    }

    // Force merge to 1 segment
    try (IndexWriter writer = new IndexWriter(tempDir)) {
      writer.forceMerge(1);
      assertThat(writer.segmentInfos().size()).isEqualTo(1);
    }

    // Reopen and assert results are identical
    try (IndexReader reader = IndexReader.open(tempDir)) {
      assertThat(reader.segmentReaders()).hasSize(1);
      IndexSearcher searcher = new IndexSearcher(reader);

      int idx = 0;
      for (String q : queries) {
        SearchResult rEx = searcher.search(q, 10, QueryMode.OR, Algo.EXHAUSTIVE);
        SearchResult rBmw = searcher.search(q, 10, QueryMode.OR, Algo.BMW);
        SearchResult rAnd = searcher.search(q, 10, QueryMode.AND, Algo.EXHAUSTIVE);

        assertHitsEqual(preMergeResults.get(idx++), rEx);
        assertHitsEqual(preMergeResults.get(idx++), rBmw);
        assertHitsEqual(preMergeResults.get(idx++), rAnd);
      }
    }
  }

  private void assertHitsEqual(SearchResult expected, SearchResult actual) {
    assertThat(actual.hits()).hasSameSizeAs(expected.hits());
    for (int i = 0; i < expected.hits().size(); i++) {
      ScoredDoc hExp = expected.hits().get(i);
      ScoredDoc hAct = actual.hits().get(i);
      assertThat(hAct.id()).isEqualTo(hExp.id());
      assertThat(hAct.score()).isCloseTo(hExp.score(), within(1e-4f));
    }
  }

  private String generateText(Random rng, int vocabSize, int length) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < length; i++) {
      if (i > 0) sb.append(' ');
      sb.append("term").append(rng.nextInt(vocabSize));
    }
    return sb.toString();
  }
}

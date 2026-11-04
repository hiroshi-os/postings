package com.postings.core.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.postings.core.index.IndexWriter;
import com.postings.core.model.Document;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearcherManagerTest {

  @Test
  void testAcquireReleaseAndRefresh(@TempDir Path tempDir) throws Exception {
    try (IndexWriter writer = new IndexWriter(tempDir)) {
      writer.addDocument(new Document("d1", "Alpha", "Initial document"));
      writer.commit();
    }

    try (SearcherManager sm = new SearcherManager(tempDir)) {
      IndexSearcher searcher1 = sm.acquire();
      try {
        SearchResult res1 = searcher1.search("alpha", 10, QueryMode.OR, Algo.EXHAUSTIVE);
        assertThat(res1.hits()).hasSize(1);
        assertThat(res1.hits().get(0).id()).isEqualTo("d1");

        // Index second doc in writer
        try (IndexWriter writer = new IndexWriter(tempDir)) {
          writer.addDocument(new Document("d2", "Beta", "Second document"));
          writer.commit();
        }

        // Before refresh, searcher1 still sees snapshot with 1 doc
        SearchResult resBefore = searcher1.search("beta", 10, QueryMode.OR, Algo.EXHAUSTIVE);
        assertThat(resBefore.hits()).isEmpty();

        // Refresh manager
        boolean refreshed = sm.maybeRefresh();
        assertThat(refreshed).isTrue();

        // New searcher sees new doc
        IndexSearcher searcher2 = sm.acquire();
        try {
          SearchResult resAfter = searcher2.search("beta", 10, QueryMode.OR, Algo.EXHAUSTIVE);
          assertThat(resAfter.hits()).hasSize(1);
          assertThat(resAfter.hits().get(0).id()).isEqualTo("d2");
        } finally {
          sm.release(searcher2);
        }

      } finally {
        sm.release(searcher1);
      }
    }
  }
}

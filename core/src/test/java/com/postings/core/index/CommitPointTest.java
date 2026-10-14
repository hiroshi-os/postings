package com.postings.core.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.postings.core.model.Document;
import com.postings.core.search.Algo;
import com.postings.core.search.IndexSearcher;
import com.postings.core.search.QueryMode;
import com.postings.core.search.SearchResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommitPointTest {

  @Test
  void testSingleWriterLock(@TempDir Path tempDir) throws Exception {
    try (IndexWriter writer1 = new IndexWriter(tempDir)) {
      assertThatThrownBy(() -> new IndexWriter(tempDir))
          .isInstanceOf(LockObtainFailedException.class);
    }

    // After closing writer1, writer2 can acquire the lock
    try (IndexWriter writer2 = new IndexWriter(tempDir)) {
      assertThat(writer2).isNotNull();
    }
  }

  @Test
  void testCommitAndDeletions(@TempDir Path tempDir) throws Exception {
    try (IndexWriter writer = new IndexWriter(tempDir)) {
      writer.addDocument(new Document("d1", "Title 1", "Search engine rankings"));
      writer.addDocument(new Document("d2", "Title 2", "Inverted index algorithms"));
      long gen1 = writer.commit();
      assertThat(gen1).isEqualTo(1L);

      try (IndexReader reader = IndexReader.open(tempDir)) {
        assertThat(reader.totalDocs()).isEqualTo(2);
        IndexSearcher searcher = new IndexSearcher(reader);
        SearchResult res = searcher.search("search", 10, QueryMode.OR, Algo.EXHAUSTIVE);
        assertThat(res.hits()).hasSize(1);
        assertThat(res.hits().get(0).id()).isEqualTo("d1");
      }

      // Delete d1 and commit
      writer.deleteById("d1");
      long gen2 = writer.commit();
      assertThat(gen2).isEqualTo(2L);

      try (IndexReader reader2 = IndexReader.open(tempDir)) {
        assertThat(reader2.totalDocs()).isEqualTo(1);
        IndexSearcher searcher = new IndexSearcher(reader2);
        SearchResult res = searcher.search("search", 10, QueryMode.OR, Algo.EXHAUSTIVE);
        assertThat(res.hits()).isEmpty();

        SearchResult res2 = searcher.search("index", 10, QueryMode.OR, Algo.EXHAUSTIVE);
        assertThat(res2.hits()).hasSize(1);
        assertThat(res2.hits().get(0).id()).isEqualTo("d2");
      }
    }
  }
}

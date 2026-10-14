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

class ModelTest {

  @Test
  void testRandomInterleavingsAgainstNaiveIndex(@TempDir Path tempDir) throws Exception {
    Random rng = new Random(12345);
    NaiveIndex naive = new NaiveIndex();

    List<String> knownIds = new ArrayList<>();
    List<Document> uncommittedDocs = new ArrayList<>();
    List<String> uncommittedDeletes = new ArrayList<>();
    int vocabSize = 40;
    int nextDocNum = 0;

    try (IndexWriter writer = new IndexWriter(tempDir)) {
      IndexReader reader = null;
      IndexSearcher searcher = null;

      try {
        // Run 100 interleaved operations
        for (int op = 0; op < 100; op++) {
          int action = rng.nextInt(6);

          switch (action) {
            case 0, 1 -> {
              // Add 1..5 docs
              int count = 1 + rng.nextInt(5);
              for (int c = 0; c < count; c++) {
                String id = "doc_" + nextDocNum++;
                knownIds.add(id);
                String body = generateText(rng, vocabSize, 15);
                Document doc = new Document(id, "Title " + id, body);
                writer.addDocument(doc);
                uncommittedDocs.add(doc);
              }
            }
            case 2 -> {
              // Delete a random doc
              if (!knownIds.isEmpty()) {
                String delId = knownIds.get(rng.nextInt(knownIds.size()));
                writer.deleteById(delId);
                uncommittedDeletes.add(delId);
              }
            }
            case 3 -> {
              // Commit and reopen reader
              writer.commit();
              for (Document d : uncommittedDocs) {
                naive.addDocument(d);
              }
              uncommittedDocs.clear();
              for (String id : uncommittedDeletes) {
                naive.deleteById(id);
              }
              uncommittedDeletes.clear();

              if (reader != null) {
                reader.close();
              }
              reader = IndexReader.open(tempDir);
              searcher = new IndexSearcher(reader);
            }
            case 4 -> {
              // Force merge
              if (writer.segmentInfos().size() > 1) {
                writer.forceMerge(1 + rng.nextInt(2));
                for (Document d : uncommittedDocs) {
                  naive.addDocument(d);
                }
                uncommittedDocs.clear();
                for (String id : uncommittedDeletes) {
                  naive.deleteById(id);
                }
                uncommittedDeletes.clear();

                if (reader != null) {
                  reader.close();
                }
                reader = IndexReader.open(tempDir);
                searcher = new IndexSearcher(reader);
              }
            }
            case 5 -> {
              // Search and verify against naive model if committed
              if (searcher != null && reader.totalDocs() > 0) {
                String q = "term" + rng.nextInt(vocabSize);
                SearchResult rPostings = searcher.search(q, 10, QueryMode.OR, Algo.EXHAUSTIVE);
                SearchResult rNaive = naive.search(q, 10, QueryMode.OR);

                assertThat(rPostings.hits()).hasSameSizeAs(rNaive.hits());
                for (int h = 0; h < rPostings.hits().size(); h++) {
                  ScoredDoc hp = rPostings.hits().get(h);
                  ScoredDoc hn = rNaive.hits().get(h);
                  assertThat(hp.id()).isEqualTo(hn.id());
                  assertThat(hp.score()).isCloseTo(hn.score(), within(1e-4f));
                }
              }
            }
          }
        }
      } finally {
        if (reader != null) {
          reader.close();
        }
      }
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

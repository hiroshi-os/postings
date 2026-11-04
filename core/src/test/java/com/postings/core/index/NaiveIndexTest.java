package com.postings.core.index;

import static org.assertj.core.api.Assertions.assertThat;

import com.postings.core.model.Document;
import com.postings.core.search.QueryMode;
import com.postings.core.search.SearchResult;
import org.junit.jupiter.api.Test;

class NaiveIndexTest {

  @Test
  void testSearchAndDeletions() {
    NaiveIndex index = new NaiveIndex();
    index.addDocument(new Document("d1", "Search Engine", "Inverted index and postings"));
    index.addDocument(new Document("d2", "Database", "Relational database index"));
    index.addDocument(new Document("d3", "Search Systems", "Distributed search query"));

    SearchResult rOr = index.search("search index", 10, QueryMode.OR);
    assertThat(rOr.hits()).hasSize(3);
    // d1 has both "search" and "index", so should score highest
    assertThat(rOr.hits().get(0).id()).isEqualTo("d1");

    SearchResult rAnd = index.search("search index", 10, QueryMode.AND);
    // Only d1 has both terms
    assertThat(rAnd.hits()).hasSize(1);
    assertThat(rAnd.hits().get(0).id()).isEqualTo("d1");

    // Delete d1
    index.deleteById("d1");
    SearchResult rAfterDel = index.search("search index", 10, QueryMode.OR);
    assertThat(rAfterDel.hits()).hasSize(2);
    assertThat(rAfterDel.hits()).noneMatch(hit -> hit.id().equals("d1"));
  }
}

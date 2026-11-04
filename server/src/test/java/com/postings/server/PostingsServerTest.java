package com.postings.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PostingsServerTest {

  @TempDir Path tempDir;
  private PostingsServer server;
  private HttpClient client;
  private String baseUrl;

  @BeforeEach
  void setUp() throws Exception {
    server = new PostingsServer(tempDir, 0);
    server.start();
    baseUrl = "http://localhost:" + server.port();
    client = HttpClient.newHttpClient();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (server != null) {
      server.close();
    }
  }

  @Test
  void testEndToEndIndexingSearchAndMetrics() throws Exception {
    // 1. Post docs
    String ndjson =
        """
        {"id":"1","title":"Information Retrieval","body":"bmw and wand algorithms for fast top-k search"}
        {"id":"2","title":"Database Systems","body":"inverted index and btree structures"}
        {"id":"3","title":"Search Engines","body":"retrieval with bm25 scoring and postings bit packing"}
        """;

    HttpRequest indexReq =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/docs"))
            .header("Content-Type", "application/x-ndjson")
            .POST(HttpRequest.BodyPublishers.ofString(ndjson))
            .build();
    HttpResponse<String> indexRes = client.send(indexReq, HttpResponse.BodyHandlers.ofString());
    assertThat(indexRes.statusCode()).isEqualTo(200);
    assertThat(indexRes.body()).contains("\"indexed\":3");

    // 2. Commit
    HttpRequest commitReq =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/commit"))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    HttpResponse<String> commitRes = client.send(commitReq, HttpResponse.BodyHandlers.ofString());
    assertThat(commitRes.statusCode()).isEqualTo(200);
    assertThat(commitRes.body()).contains("\"status\":\"ok\"");

    // 3. Stats
    HttpRequest statsReq =
        HttpRequest.newBuilder().uri(URI.create(baseUrl + "/stats")).GET().build();
    HttpResponse<String> statsRes = client.send(statsReq, HttpResponse.BodyHandlers.ofString());
    assertThat(statsRes.statusCode()).isEqualTo(200);
    assertThat(statsRes.body()).contains("\"totalDocs\":3");

    // 4. Search OR with BMW
    HttpRequest searchReq =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/search?q=retrieval+scoring&k=10&mode=or&algo=bmw"))
            .GET()
            .build();
    HttpResponse<String> searchRes = client.send(searchReq, HttpResponse.BodyHandlers.ofString());
    assertThat(searchRes.statusCode()).isEqualTo(200);
    assertThat(searchRes.body()).contains("\"id\":\"1\"");
    assertThat(searchRes.body()).contains("\"docsScored\":");
    assertThat(searchRes.body()).contains("\"blocksSkipped\":");

    // 5. Search AND
    HttpRequest andReq =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/search?q=bm25+postings&k=5&mode=and&algo=bmw"))
            .GET()
            .build();
    HttpResponse<String> andRes = client.send(andReq, HttpResponse.BodyHandlers.ofString());
    assertThat(andRes.statusCode()).isEqualTo(200);
    assertThat(andRes.body()).contains("\"id\":\"3\"");

    // 6. Delete doc 1
    HttpRequest delReq =
        HttpRequest.newBuilder().uri(URI.create(baseUrl + "/docs/1")).DELETE().build();
    HttpResponse<String> delRes = client.send(delReq, HttpResponse.BodyHandlers.ofString());
    assertThat(delRes.statusCode()).isEqualTo(200);
    assertThat(delRes.body()).contains("\"deleted\":\"1\"");

    // Commit delete
    client.send(commitReq, HttpResponse.BodyHandlers.ofString());

    // Search again and verify doc 1 is gone
    HttpResponse<String> searchAfterDel =
        client.send(searchReq, HttpResponse.BodyHandlers.ofString());
    assertThat(searchAfterDel.body()).doesNotContain("\"id\":\"1\"");

    // 7. Scrape Prometheus metrics
    HttpRequest metricsReq =
        HttpRequest.newBuilder().uri(URI.create(baseUrl + "/metrics")).GET().build();
    HttpResponse<String> metricsRes = client.send(metricsReq, HttpResponse.BodyHandlers.ofString());
    assertThat(metricsRes.statusCode()).isEqualTo(200);
    assertThat(metricsRes.headers().firstValue("Content-Type").orElse("")).contains("text/plain");
    assertThat(metricsRes.body()).contains("postings_query_latency_seconds");
    assertThat(metricsRes.body()).contains("postings_docs_scored_total");
    assertThat(metricsRes.body()).contains("postings_segments_count");
  }
}

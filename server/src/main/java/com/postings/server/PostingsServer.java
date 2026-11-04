package com.postings.server;

import com.postings.core.index.IndexWriter;
import com.postings.core.model.Document;
import com.postings.core.model.ScoredDoc;
import com.postings.core.search.Algo;
import com.postings.core.search.IndexSearcher;
import com.postings.core.search.QueryMode;
import com.postings.core.search.SearchResult;
import com.postings.core.search.SearcherManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lightweight HTTP server exposing index manipulation, search, stats, and Prometheus metrics. */
public final class PostingsServer implements AutoCloseable {

  private final HttpServer server;
  private final IndexWriter writer;
  private final SearcherManager searcherManager;
  private final PrometheusMeterRegistry prometheusRegistry;

  private final Timer queryTimer;
  private final Counter docsScoredCounter;
  private final Counter blocksSkippedCounter;

  public PostingsServer(Path indexDir, int port) throws IOException {
    this.writer = new IndexWriter(indexDir);
    this.searcherManager = new SearcherManager(indexDir);
    this.prometheusRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

    this.queryTimer =
        Timer.builder("postings_query_latency_seconds")
            .description("Query execution latency")
            .publishPercentileHistogram()
            .register(prometheusRegistry);
    this.docsScoredCounter =
        Counter.builder("postings_docs_scored_total")
            .description("Total number of documents scored during queries")
            .register(prometheusRegistry);
    this.blocksSkippedCounter =
        Counter.builder("postings_blocks_skipped_total")
            .description("Total number of skip blocks pruned by Block-Max WAND")
            .register(prometheusRegistry);

    Gauge.builder(
            "postings_segments_count",
            () -> {
              try {
                IndexSearcher s = searcherManager.acquire();
                try {
                  return s.reader().segmentReaders().size();
                } finally {
                  searcherManager.release(s);
                }
              } catch (Exception e) {
                return 0;
              }
            })
        .description("Current number of active index segments")
        .register(prometheusRegistry);

    this.server = HttpServer.create(new InetSocketAddress(port), 0);
    this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

    server.createContext("/docs", new DocsHandler());
    server.createContext("/commit", new CommitHandler());
    server.createContext("/search", new SearchHandler());
    server.createContext("/stats", new StatsHandler());
    server.createContext("/metrics", new MetricsHandler());
  }

  public void start() {
    server.start();
  }

  public int port() {
    return server.getAddress().getPort();
  }

  @Override
  public void close() throws IOException {
    server.stop(0);
    searcherManager.close();
    writer.close();
  }

  public static void main(String[] args) throws Exception {
    int port = 8080;
    Path path = Paths.get("/tmp/idx-postings");
    if (args.length > 0) {
      path = Paths.get(args[0]);
    }
    if (args.length > 1) {
      port = Integer.parseInt(args[1]);
    }

    PostingsServer app = new PostingsServer(path, port);
    app.start();
    System.out.println("Postings server running at http://localhost:" + app.port() + "/");
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    app.close();
                  } catch (IOException e) {
                    // Ignored on shutdown
                  }
                }));
  }

  // --- Handlers ---

  private final class DocsHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      String method = exchange.getRequestMethod();
      String path = exchange.getRequestURI().getPath();

      if ("POST".equalsIgnoreCase(method)) {
        // Bulk NDJSON indexing
        int count = 0;
        try (BufferedReader reader =
            new BufferedReader(
                new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
          String line;
          while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            Document doc = parseDocJson(line);
            if (doc != null) {
              writer.addDocument(doc);
              count++;
            }
          }
        }
        sendJsonResponse(exchange, 200, "{\"status\":\"ok\",\"indexed\":" + count + "}");
      } else if ("DELETE".equalsIgnoreCase(method)) {
        // /docs/{id}
        String prefix = "/docs/";
        if (path.startsWith(prefix) && path.length() > prefix.length()) {
          String id = path.substring(prefix.length());
          writer.deleteById(id);
          sendJsonResponse(
              exchange, 200, "{\"status\":\"ok\",\"deleted\":\"" + escapeJson(id) + "\"}");
        } else {
          sendJsonResponse(exchange, 400, "{\"error\":\"Missing document id in path /docs/{id}\"}");
        }
      } else {
        exchange.sendResponseHeaders(405, -1);
      }
    }
  }

  private final class CommitHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
        exchange.sendResponseHeaders(405, -1);
        return;
      }
      long gen = writer.commit();
      searcherManager.maybeRefresh();
      sendJsonResponse(exchange, 200, "{\"status\":\"ok\",\"generation\":" + gen + "}");
    }
  }

  private final class SearchHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
        exchange.sendResponseHeaders(405, -1);
        return;
      }

      Map<String, String> params = parseQueryParams(exchange.getRequestURI().getRawQuery());
      String q = params.getOrDefault("q", "");
      int k = parseInt(params.get("k"), 10);
      QueryMode mode = parseQueryMode(params.get("mode"));
      Algo algo = parseAlgo(params.get("algo"));

      Timer.Sample sample = Timer.start(prometheusRegistry);
      IndexSearcher searcher = searcherManager.acquire();
      SearchResult result;
      try {
        result = searcher.search(q, k, mode, algo);
      } finally {
        searcherManager.release(searcher);
        sample.stop(queryTimer);
      }

      docsScoredCounter.increment((double) result.docsScored());
      blocksSkippedCounter.increment((double) result.blocksSkipped());

      StringBuilder sb = new StringBuilder();
      sb.append("{\"hits\":[");
      for (int i = 0; i < result.hits().size(); i++) {
        if (i > 0) sb.append(',');
        ScoredDoc h = result.hits().get(i);
        sb.append("{\"id\":\"").append(escapeJson(h.id())).append("\",");
        sb.append("\"score\":").append(h.score()).append(",");
        sb.append("\"title\":\"").append(escapeJson(h.title())).append("\"}");
      }
      sb.append("],");
      sb.append("\"tookMicros\":").append(result.tookMicros()).append(",");
      sb.append("\"docsScored\":").append(result.docsScored()).append(",");
      sb.append("\"blocksSkipped\":").append(result.blocksSkipped()).append("}");

      sendJsonResponse(exchange, 200, sb.toString());
    }
  }

  private final class StatsHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
        exchange.sendResponseHeaders(405, -1);
        return;
      }

      IndexSearcher searcher = searcherManager.acquire();
      try {
        int totalDocs = searcher.reader().totalDocs();
        int maxDoc = searcher.reader().maxDoc();
        int numSegments = searcher.reader().segmentReaders().size();
        long gen = searcher.reader().generation();

        String json =
            "{\"totalDocs\":"
                + totalDocs
                + ",\"maxDoc\":"
                + maxDoc
                + ",\"numSegments\":"
                + numSegments
                + ",\"generation\":"
                + gen
                + "}";
        sendJsonResponse(exchange, 200, json);
      } finally {
        searcherManager.release(searcher);
      }
    }
  }

  private final class MetricsHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
        exchange.sendResponseHeaders(405, -1);
        return;
      }
      String scrape = prometheusRegistry.scrape();
      byte[] bytes = scrape.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
      exchange.sendResponseHeaders(200, bytes.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(bytes);
      }
    }
  }

  // --- Utility Methods ---

  private static void sendJsonResponse(HttpExchange exchange, int statusCode, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(statusCode, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }

  private static Map<String, String> parseQueryParams(String rawQuery) {
    Map<String, String> params = new HashMap<>();
    if (rawQuery == null || rawQuery.isEmpty()) {
      return params;
    }
    java.util.StringTokenizer tokenizer = new java.util.StringTokenizer(rawQuery, "&");
    while (tokenizer.hasMoreTokens()) {
      String pair = tokenizer.nextToken();
      int eq = pair.indexOf('=');
      if (eq > 0) {
        String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
        String val = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
        params.put(key, val);
      }
    }
    return params;
  }

  private static int parseInt(String val, int def) {
    if (val == null) return def;
    try {
      return Integer.parseInt(val);
    } catch (NumberFormatException e) {
      return def;
    }
  }

  private static QueryMode parseQueryMode(String mode) {
    if ("and".equalsIgnoreCase(mode)) return QueryMode.AND;
    return QueryMode.OR;
  }

  private static Algo parseAlgo(String algo) {
    if ("wand".equalsIgnoreCase(algo)) return Algo.WAND;
    if ("exhaustive".equalsIgnoreCase(algo)) return Algo.EXHAUSTIVE;
    return Algo.BMW;
  }

  private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]*)\"");
  private static final Pattern TITLE_PATTERN = Pattern.compile("\"title\"\\s*:\\s*\"([^\"]*)\"");
  private static final Pattern BODY_PATTERN = Pattern.compile("\"body\"\\s*:\\s*\"([^\"]*)\"");

  static Document parseDocJson(String json) {
    Matcher mId = ID_PATTERN.matcher(json);
    if (!mId.find()) return null;
    String id = unescapeJson(mId.group(1));

    String title = "";
    Matcher mTitle = TITLE_PATTERN.matcher(json);
    if (mTitle.find()) {
      title = unescapeJson(mTitle.group(1));
    }

    String body = "";
    Matcher mBody = BODY_PATTERN.matcher(json);
    if (mBody.find()) {
      body = unescapeJson(mBody.group(1));
    }

    return new Document(id, title, body);
  }

  static String escapeJson(String s) {
    if (s == null) return "";
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\b' -> sb.append("\\b");
        case '\f' -> sb.append("\\f");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < ' ') {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    return sb.toString();
  }

  static String unescapeJson(String s) {
    if (s == null) return "";
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\\' && i + 1 < s.length()) {
        char next = s.charAt(i + 1);
        switch (next) {
          case '"' -> {
            sb.append('"');
            i++;
          }
          case '\\' -> {
            sb.append('\\');
            i++;
          }
          case '/' -> {
            sb.append('/');
            i++;
          }
          case 'b' -> {
            sb.append('\b');
            i++;
          }
          case 'f' -> {
            sb.append('\f');
            i++;
          }
          case 'n' -> {
            sb.append('\n');
            i++;
          }
          case 'r' -> {
            sb.append('\r');
            i++;
          }
          case 't' -> {
            sb.append('\t');
            i++;
          }
          default -> sb.append(c);
        }
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }
}

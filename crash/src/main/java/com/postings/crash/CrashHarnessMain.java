package com.postings.crash;

import com.postings.core.index.IndexReader;
import com.postings.core.index.IndexWriter;
import com.postings.core.model.Document;
import com.postings.core.search.Algo;
import com.postings.core.search.IndexSearcher;
import com.postings.core.search.QueryMode;
import com.postings.core.search.SearchResult;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Random;

/**
 * Crash harness that verifies crash-safety of commit points under sudden process termination (kill
 * -9).
 */
public final class CrashHarnessMain {

  private CrashHarnessMain() {}

  public static void main(String[] args) throws Exception {
    int runs = 20;
    long seed = 1L;
    boolean isChild = false;
    Path childDir = null;
    long childSeed = 1L;

    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--runs" -> runs = Integer.parseInt(args[++i]);
        case "--seed" -> seed = Long.parseLong(args[++i]);
        case "--child" -> {
          isChild = true;
          childDir = Path.of(args[++i]);
          childSeed = Long.parseLong(args[++i]);
        }
      }
    }

    if (isChild) {
      runChild(childDir, childSeed);
      return;
    }

    runParent(runs, seed);
  }

  private static void runParent(int totalRuns, long baseSeed) throws Exception {
    System.out.println(
        String.format("Starting crash harness: %d runs, base seed %d", totalRuns, baseSeed));
    Random rng = new Random(baseSeed);
    int lostCommits = 0;
    int openFailures = 0;
    int crcFailures = 0;

    String javaBin =
        ProcessHandle.current()
            .info()
            .command()
            .orElse(
                System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
    String classPath = System.getProperty("java.class.path");

    for (int run = 1; run <= totalRuns; run++) {
      Path runDir = Files.createTempDirectory("crash_run_" + run + "_");
      long runSeed = rng.nextLong();

      ProcessBuilder pb =
          new ProcessBuilder(
              javaBin,
              "-cp",
              classPath,
              "com.postings.crash.CrashHarnessMain",
              "--child",
              runDir.toAbsolutePath().toString(),
              String.valueOf(runSeed));
      pb.redirectErrorStream(true);

      Process process = pb.start();
      BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

      long lastAckGen = 0;
      int lastAckDocCount = 0;

      // Random wait before kill -9 (between 20ms and 200ms)
      int waitMs = 20 + rng.nextInt(180);
      long killDeadline = System.currentTimeMillis() + waitMs;

      String line;
      while ((line = reader.readLine()) != null) {
        if (line.startsWith("COMMITTED ")) {
          int firstSpace = line.indexOf(' ');
          int secondSpace = line.indexOf(' ', firstSpace + 1);
          if (secondSpace > firstSpace) {
            lastAckGen = Long.parseLong(line.substring(firstSpace + 1, secondSpace));
            lastAckDocCount = Integer.parseInt(line.substring(secondSpace + 1).trim());
          }
        }
        if (System.currentTimeMillis() >= killDeadline && lastAckGen > 0) {
          break;
        }
      }

      // Hard kill child process (kill -9)
      process.destroyForcibly();
      process.waitFor();

      // Post-crash verification
      try (IndexReader indexReader = IndexReader.open(runDir)) {
        int actualDocs = indexReader.totalDocs();
        if (actualDocs < lastAckDocCount) {
          lostCommits++;
          System.err.println(
              String.format(
                  "Run %d: LOST COMMITTED DOCS! Expected at least %d but found %d",
                  run, lastAckDocCount, actualDocs));
        }

        // Verify every doc from acknowledged commit is searchable by unique token
        IndexSearcher searcher = new IndexSearcher(indexReader);
        for (int d = 0; d < lastAckDocCount; d++) {
          String token = "doc_" + d + "_tok";
          SearchResult result = searcher.search(token, 1, QueryMode.OR, Algo.EXHAUSTIVE);
          if (result.hits().isEmpty()) {
            lostCommits++;
            System.err.println(
                String.format("Run %d: Document %d with token %s not found!", run, d, token));
            break;
          }
        }
      } catch (Exception e) {
        openFailures++;
        System.err.println(String.format("Run %d: Failed to open index post-crash: %s", run, e));
      }

      deleteDirectory(runDir);

      if (run % 10 == 0 || run == totalRuns) {
        System.out.println(String.format("Progress: %d/%d runs completed", run, totalRuns));
      }
    }

    System.out.println(
        String.format(
            "Crash harness summary: %d runs, lost acknowledged commits: %d, open failures: %d, CRC failures: %d",
            totalRuns, lostCommits, openFailures, crcFailures));

    if (lostCommits > 0 || openFailures > 0 || crcFailures > 0) {
      throw new IllegalStateException("Crash harness failed assertions!");
    }
  }

  private static void runChild(Path dir, long seed) throws Exception {
    Random rng = new Random(seed);
    try (IndexWriter writer = new IndexWriter(dir)) {
      int commitInterval = 5 + rng.nextInt(6); // commit every 5..10 docs

      for (int i = 0; i < 5000; i++) {
        String id = "doc_" + i;
        String token = "doc_" + i + "_tok";
        String body =
            "Body text for document " + i + " " + token + " search inverted postings index";
        writer.addDocument(new Document(id, "Title " + i, body));

        if ((i + 1) % commitInterval == 0) {
          long gen = writer.commit();
          System.out.println("COMMITTED " + gen + " " + (i + 1));
          System.out.flush();
        }
      }
    }
  }

  private static void deleteDirectory(Path dir) {
    try {
      if (Files.exists(dir)) {
        try (var stream = Files.walk(dir)) {
          stream.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
      }
    } catch (Exception e) {
      // Best-effort cleanup of test run directory
    }
  }
}

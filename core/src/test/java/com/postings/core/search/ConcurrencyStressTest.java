package com.postings.core.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.postings.core.index.IndexWriter;
import com.postings.core.model.Document;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConcurrencyStressTest {

  @Test
  void test8SearchersWithConcurrentWriter(@TempDir Path tempDir) throws Exception {
    long durationSeconds = Long.getLong("postings.concurrency.duration", 10);
    System.out.println("Running ConcurrencyStressTest for " + durationSeconds + " seconds...");

    // Seed initial index with some docs so searchers have content
    try (IndexWriter writer = new IndexWriter(tempDir)) {
      for (int i = 0; i < 50; i++) {
        writer.addDocument(new Document("doc_" + i, "Title " + i, "seed term" + (i % 10)));
      }
      writer.commit();
    }

    SearcherManager searcherManager = new SearcherManager(tempDir);
    AtomicBoolean stop = new AtomicBoolean(false);
    AtomicLong totalSearches = new AtomicLong(0);
    AtomicLong totalWrites = new AtomicLong(0);
    List<Throwable> exceptions = new java.util.concurrent.CopyOnWriteArrayList<>();

    // Map generation -> known committed doc count floor
    ConcurrentMap<Long, Integer> committedGenerations = new ConcurrentHashMap<>();

    // 1 writer thread
    Thread writerThread =
        new Thread(
            () -> {
              try (IndexWriter writer = new IndexWriter(tempDir)) {
                Random rng = new Random(111);
                int docCounter = 100;
                while (!stop.get()) {
                  int op = rng.nextInt(4);
                  if (op == 0 || op == 1) {
                    // Add
                    String id = "doc_" + docCounter++;
                    writer.addDocument(
                        new Document(id, "Title " + id, "term" + rng.nextInt(20) + " common"));
                    totalWrites.incrementAndGet();
                  } else if (op == 2) {
                    // Delete
                    writer.deleteById("doc_" + (100 + rng.nextInt(Math.max(1, docCounter - 100))));
                  } else {
                    // Commit or merge
                    if (rng.nextBoolean()) {
                      long gen = writer.commit();
                      committedGenerations.put(gen, writer.segmentInfos().size());
                    } else {
                      writer.forceMerge(2);
                      committedGenerations.put(
                          writer.segmentInfos().generation(), writer.segmentInfos().size());
                    }
                  }
                  Thread.sleep(rng.nextInt(5));
                }
                writer.commit();
              } catch (Throwable t) {
                exceptions.add(t);
              }
            },
            "writer-thread");

    // 8 searcher threads
    int numSearchers = 8;
    ExecutorService searcherPool = Executors.newFixedThreadPool(numSearchers);
    List<Future<?>> searcherFutures = new ArrayList<>();
    CountDownLatch startLatch = new CountDownLatch(1);

    for (int s = 0; s < numSearchers; s++) {
      final int searcherId = s;
      searcherFutures.add(
          searcherPool.submit(
              () -> {
                Random rng = new Random(200 + searcherId);
                try {
                  startLatch.await();
                  while (!stop.get()) {
                    searcherManager.maybeRefresh();
                    IndexSearcher searcher = searcherManager.acquire();
                    try {
                      assertThat(searcher.reader().generation()).isGreaterThanOrEqualTo(1);
                      String q = rng.nextBoolean() ? "common" : "term" + rng.nextInt(20);
                      SearchResult res =
                          searcher.search(
                              q,
                              10,
                              rng.nextBoolean() ? QueryMode.OR : QueryMode.AND,
                              rng.nextBoolean() ? Algo.BMW : Algo.EXHAUSTIVE);
                      assertThat(res.hits()).isNotNull();
                      totalSearches.incrementAndGet();
                    } finally {
                      searcherManager.release(searcher);
                    }
                    Thread.sleep(rng.nextInt(3));
                  }
                } catch (Throwable t) {
                  exceptions.add(t);
                }
              }));
    }

    writerThread.start();
    startLatch.countDown();

    Thread.sleep(TimeUnit.SECONDS.toMillis(durationSeconds));
    stop.set(true);

    writerThread.join(10000);
    for (Future<?> f : searcherFutures) {
      f.get(10, TimeUnit.SECONDS);
    }
    searcherPool.shutdown();
    searcherPool.awaitTermination(10, TimeUnit.SECONDS);
    searcherManager.close();

    assertThat(exceptions).isEmpty();
    assertThat(committedGenerations).isNotEmpty();
    assertThat(totalSearches.get()).isGreaterThan(0);
    assertThat(totalWrites.get()).isGreaterThan(0);
    System.out.println(
        "ConcurrencyStressTest completed successfully: "
            + totalSearches.get()
            + " searches, "
            + totalWrites.get()
            + " writes.");
  }
}

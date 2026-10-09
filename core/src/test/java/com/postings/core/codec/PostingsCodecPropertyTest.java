package com.postings.core.codec;

import static org.assertj.core.api.Assertions.assertThat;

import com.postings.core.index.PostingsWriter;
import com.postings.core.index.SegmentPostingsIterator;
import com.postings.core.search.BM25Scorer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class PostingsCodecPropertyTest {

  private final BM25Scorer scorer = new BM25Scorer();

  @Property
  void encodeDecodeIdentityAndAdvance(@ForAll("sortedDocIds") List<Integer> docIdsList)
      throws Exception {
    int size = docIdsList.size();
    if (size == 0) {
      return;
    }

    int[] docIds = docIdsList.stream().mapToInt(Integer::intValue).toArray();
    int[] freqs = new int[size];
    Arrays.fill(freqs, 1);
    int maxDoc = docIds[size - 1] + 1;
    int[] docLengths = new int[maxDoc];
    Arrays.fill(docLengths, 10);

    Path tempFile = Files.createTempFile("test_post", ".post");
    try {
      long offset;
      float maxScore;
      try (PostingsWriter writer = new PostingsWriter(tempFile, scorer, 10.0f)) {
        PostingsWriter.PostingsMeta meta =
            writer.writePostings(docIds, freqs, size, docLengths, 1.5f);
        offset = meta.offset();
        maxScore = meta.maxScore();
      }

      // Verify nextDoc() sequence equals original
      try (FileChannel channel = FileChannel.open(tempFile, StandardOpenOption.READ)) {
        SegmentPostingsIterator iter = new SegmentPostingsIterator(channel, offset, maxScore);
        List<Integer> decoded = new ArrayList<>();
        int doc;
        while ((doc = iter.nextDoc()) != SegmentPostingsIterator.NO_MORE_DOCS) {
          decoded.add(doc);
        }
        assertThat(decoded).containsExactlyElementsOf(docIdsList);
      }

      // Verify advance(target) equals linear scan
      Random rng = new Random(size * 31L);
      for (int t = 0; t < 10; t++) {
        int target = rng.nextInt(maxDoc + 10);
        int expected = SegmentPostingsIterator.NO_MORE_DOCS;
        for (int d : docIds) {
          if (d >= target) {
            expected = d;
            break;
          }
        }

        try (FileChannel channel = FileChannel.open(tempFile, StandardOpenOption.READ)) {
          SegmentPostingsIterator iter = new SegmentPostingsIterator(channel, offset, maxScore);
          int advanced = iter.advance(target);
          assertThat(advanced).isEqualTo(expected);
        }
      }
    } finally {
      Files.deleteIfExists(tempFile);
    }
  }

  @Provide
  Arbitrary<List<Integer>> sortedDocIds() {
    return Arbitraries.integers()
        .between(0, 5000)
        .list()
        .ofMinSize(1)
        .ofMaxSize(300)
        .map(
            list -> {
              int[] arr = list.stream().mapToInt(i -> i).distinct().sorted().toArray();
              List<Integer> sorted = new ArrayList<>(arr.length);
              for (int v : arr) {
                sorted.add(v);
              }
              return sorted;
            });
  }
}

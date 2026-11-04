package com.postings.core.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.postings.core.codec.CorruptIndexException;
import com.postings.core.model.Document;
import com.postings.core.search.BM25Scorer;
import com.postings.core.search.PostingsIterator;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SegmentRoundTripTest {

  @Test
  void testSegmentWriteAndRead(@TempDir Path tempDir) throws Exception {
    MemoryIndexBuffer buffer = new MemoryIndexBuffer();
    buffer.addDocument(
        new Document("doc1", "Information Retrieval", "Inverted index and postings"));
    buffer.addDocument(new Document("doc2", "Database Systems", "B-tree index and transactions"));
    buffer.addDocument(new Document("doc3", "Distributed Search", "WAND and Block-Max WAND"));

    BM25Scorer scorer = new BM25Scorer();
    SegmentWriter.writeSegment(tempDir, "_0", buffer, scorer);

    try (SegmentReader reader = new SegmentReader(tempDir, "_0")) {
      assertThat(reader.maxDoc()).isEqualTo(3);
      assertThat(reader.numDocs()).isEqualTo(3);

      Document doc1 = reader.document(0);
      assertThat(doc1.id()).isEqualTo("doc1");
      assertThat(doc1.title()).isEqualTo("Information Retrieval");

      assertThat(reader.docLength(0))
          .isEqualTo(6); // "information", "retrieval", "inverted", "index", "and", "postings"

      // Check dictionary and postings
      PostingsIterator iter = reader.postings("index");
      assertThat(iter).isNotNull();
      assertThat(iter.nextDoc()).isEqualTo(0);
      assertThat(iter.docId()).isEqualTo(0);
      assertThat(iter.nextDoc()).isEqualTo(1);
      assertThat(iter.docId()).isEqualTo(1);
      assertThat(iter.nextDoc()).isEqualTo(PostingsIterator.NO_MORE_DOCS);

      PostingsIterator missing = reader.postings("nonexistent");
      assertThat(missing).isNull();
    }
  }

  @Test
  void testCrcCorruptionDetection(@TempDir Path tempDir) throws Exception {
    MemoryIndexBuffer buffer = new MemoryIndexBuffer();
    buffer.addDocument(new Document("doc1", "Title", "Body content"));

    BM25Scorer scorer = new BM25Scorer();
    SegmentWriter.writeSegment(tempDir, "_0", buffer, scorer);

    // Corrupt one byte in the middle of _0.len
    Path lenFile = tempDir.resolve("_0.len");
    try (FileChannel fc =
        FileChannel.open(lenFile, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
      ByteBuffer b = ByteBuffer.allocate(1);
      fc.read(b, 5);
      b.flip();
      byte orig = b.get(0);
      b.put(0, (byte) (orig ^ 0xFF));
      b.position(0);
      fc.write(b, 5);
    }

    assertThatThrownBy(() -> new SegmentReader(tempDir, "_0"))
        .isInstanceOf(CorruptIndexException.class);
  }
}

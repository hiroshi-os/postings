package com.postings.core.index;

import static org.assertj.core.api.Assertions.assertThat;

import com.postings.core.model.Document;
import org.junit.jupiter.api.Test;

class MemoryIndexBufferTest {

  @Test
  void testAddAndRetrieve() {
    MemoryIndexBuffer buffer = new MemoryIndexBuffer();
    int doc0 = buffer.addDocument(new Document("doc1", "Alpha", "Beta gamma beta"));
    int doc1 = buffer.addDocument(new Document("doc2", "Delta", "Beta"));

    assertThat(doc0).isEqualTo(0);
    assertThat(doc1).isEqualTo(1);
    assertThat(buffer.numDocs()).isEqualTo(2);

    assertThat(buffer.externalId(0)).isEqualTo("doc1");
    assertThat(buffer.externalId(1)).isEqualTo("doc2");

    // doc0 length = 4 ("alpha", "beta", "gamma", "beta")
    assertThat(buffer.docLength(0)).isEqualTo(4);
    // doc1 length = 2 ("delta", "beta")
    assertThat(buffer.docLength(1)).isEqualTo(2);

    assertThat(buffer.terms().keySet()).containsExactly("alpha", "beta", "delta", "gamma");

    MemoryIndexBuffer.PostingListBuilder betaPostings = buffer.terms().get("beta");
    assertThat(betaPostings.size()).isEqualTo(2);
    assertThat(betaPostings.docIds()[0]).isEqualTo(0);
    assertThat(betaPostings.freqs()[0]).isEqualTo(2);
    assertThat(betaPostings.docIds()[1]).isEqualTo(1);
    assertThat(betaPostings.freqs()[1]).isEqualTo(1);

    assertThat(buffer.estimatedRamBytes()).isPositive();
  }
}

package com.postings.core.codec;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class CorruptCodecPropertyTest {

  @Property
  void arbitraryBytesThrowCleanCorruptExceptionInVInt(@ForAll("randomBytes") byte[] bytes) {
    ByteBuffer buf = ByteBuffer.wrap(bytes);
    try {
      while (buf.hasRemaining()) {
        VInt.readVInt(buf);
      }
    } catch (CorruptIndexException expected) {
      // Clean exception as required
      assertThat(expected).isNotNull();
    }
  }

  @Property
  void arbitraryBytesThrowCleanCorruptExceptionInBitPacking(
      @ForAll("randomBytes") byte[] bytes, @ForAll("bitWidths") int bitWidth) {
    int[] out = new int[BitPacking.BLOCK_SIZE];
    try {
      BitPacking.unpack(bytes, 0, out, 0, bitWidth);
    } catch (CorruptIndexException expected) {
      // Clean exception as required
      assertThat(expected).isNotNull();
    }
  }

  @Provide
  Arbitrary<byte[]> randomBytes() {
    return Arbitraries.bytes().array(byte[].class).ofMinSize(0).ofMaxSize(256);
  }

  @Provide
  Arbitrary<Integer> bitWidths() {
    return Arbitraries.integers().between(0, 32);
  }
}

package com.postings.core.codec;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class BitPackingPropertyTest {

  @Property
  void roundTripAllBitWidths(@ForAll("bitWidths") int bitWidth) throws Exception {
    int[] original = new int[BitPacking.BLOCK_SIZE];
    long mask = (bitWidth == 32) ? 0xFFFFFFFFL : ((1L << bitWidth) - 1);

    Random rng = new Random(bitWidth * 31L);
    for (int i = 0; i < BitPacking.BLOCK_SIZE; i++) {
      if (bitWidth == 0) {
        original[i] = 0;
      } else if (bitWidth == 32) {
        original[i] = rng.nextInt();
      } else {
        original[i] = (int) (rng.nextLong() & mask);
      }
    }

    int numBytes = BitPacking.numBytes(bitWidth);
    byte[] packed = new byte[numBytes];
    BitPacking.pack(original, 0, packed, 0, bitWidth);

    int[] decoded = new int[BitPacking.BLOCK_SIZE];
    BitPacking.unpack(packed, 0, decoded, 0, bitWidth);

    assertThat(decoded).containsExactly(original);
  }

  @Provide
  Arbitrary<Integer> bitWidths() {
    return Arbitraries.integers().between(0, 32);
  }
}

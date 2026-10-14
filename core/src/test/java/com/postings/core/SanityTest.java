package com.postings.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SanityTest {
  @Test
  void testConstants() {
    assertThat(Constants.NO_MORE_DOCS).isEqualTo(Integer.MAX_VALUE);
    assertThat(Constants.BLOCK_SIZE).isEqualTo(128);
  }
}

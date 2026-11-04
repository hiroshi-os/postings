package com.postings.core.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AnalyzerTest {

  private final Analyzer analyzer = DefaultAnalyzer.INSTANCE;

  @Test
  void testBasicTokenizationAndLowercasing() {
    List<String> tokens = analyzer.analyze("The Quick Brown FOX jumps over 42 lazy Dogs!");
    assertThat(tokens)
        .containsExactly("the", "quick", "brown", "fox", "jumps", "over", "42", "lazy", "dogs");
  }

  @Test
  void testNfkcNormalization() {
    // 'ﬁ' is U+FB01 Latin Small Ligature Fi -> 'fi' in NFKC
    // 'ＡＢＣ' are full-width uppercase -> 'abc'
    List<String> tokens = analyzer.analyze("ﬁle ＡＢＣ 123");
    assertThat(tokens).containsExactly("file", "abc", "123");
  }

  @Test
  void testDropsTokensLongerThan64Chars() {
    String len64 = "a".repeat(64);
    String len65 = "b".repeat(65);
    String input = "short " + len64 + " " + len65 + " end";

    List<String> tokens = analyzer.analyze(input);
    assertThat(tokens).containsExactly("short", len64, "end");
  }

  @Test
  void testEmptyAndPunctuationOnly() {
    assertThat(analyzer.analyze("")).isEmpty();
    assertThat(analyzer.analyze(null)).isEmpty();
    assertThat(analyzer.analyze("   \t\n   ")).isEmpty();
    assertThat(analyzer.analyze("!@#$%^&*()_+-=[]{}|;':\",./<>?")).isEmpty();
  }

  @Test
  void testUnicodeLettersAndDigits() {
    // German umlauts, French accents, Cyrillic, CJK
    List<String> tokens = analyzer.analyze("Über café Москва 日本語");
    assertThat(tokens).containsExactly("über", "café", "москва", "日本語");
  }
}

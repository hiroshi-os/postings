package com.postings.core.analysis;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Default analyzer matching the specification: - NFKC Unicode normalization - Tokenization on runs
 * of Unicode letters and digits - Lowercasing with Locale.ROOT - Drops tokens longer than 64
 * characters
 */
public final class DefaultAnalyzer implements Analyzer {

  public static final int MAX_TOKEN_LENGTH = 64;
  public static final DefaultAnalyzer INSTANCE = new DefaultAnalyzer();

  @Override
  public List<String> analyze(String text) {
    if (text == null || text.isEmpty()) {
      return List.of();
    }

    String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
    List<String> tokens = new ArrayList<>();
    int length = normalized.length();
    int start = -1;

    for (int i = 0; i < length; ) {
      int cp = normalized.codePointAt(i);
      int charCount = Character.charCount(cp);

      if (Character.isLetterOrDigit(cp)) {
        if (start < 0) {
          start = i;
        }
      } else {
        if (start >= 0) {
          emitToken(normalized, start, i, tokens);
          start = -1;
        }
      }
      i += charCount;
    }

    if (start >= 0) {
      emitToken(normalized, start, length, tokens);
    }

    return tokens;
  }

  private void emitToken(String text, int start, int end, List<String> out) {
    int tokenCharLength = end - start;
    if (tokenCharLength > 0 && tokenCharLength <= MAX_TOKEN_LENGTH) {
      String rawToken = text.substring(start, end);
      String lower = rawToken.toLowerCase(Locale.ROOT);
      if (lower.length() <= MAX_TOKEN_LENGTH) {
        out.add(lower);
      }
    }
  }
}

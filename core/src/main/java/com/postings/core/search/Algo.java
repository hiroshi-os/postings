package com.postings.core.search;

import java.util.Locale;

/** Search algorithm used for disjunctive (OR) queries. */
public enum Algo {
  EXHAUSTIVE,
  WAND,
  BMW;

  public static Algo fromString(String val) {
    if (val == null) {
      return BMW;
    }
    return switch (val.trim().toLowerCase(Locale.ROOT)) {
      case "exhaustive" -> EXHAUSTIVE;
      case "wand" -> WAND;
      case "bmw" -> BMW;
      default -> BMW;
    };
  }
}

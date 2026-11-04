package com.postings.core.search;

/** Query execution mode: OR (disjunction) or AND (conjunction). */
public enum QueryMode {
  OR,
  AND;

  public static QueryMode fromString(String val) {
    if (val == null) {
      return OR;
    }
    return switch (val.trim().toLowerCase(java.util.Locale.ROOT)) {
      case "and" -> AND;
      default -> OR;
    };
  }
}

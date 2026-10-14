package com.postings.core.index;

/** Dictionary entry containing term metadata. */
public record TermEntry(String term, int df, long postingsOffset, float maxScore) {}

package com.postings.core.search;

import com.postings.core.model.ScoredDoc;
import java.util.List;

/** Result of a search query containing top-k hits and execution metrics. */
public record SearchResult(
    List<ScoredDoc> hits, long tookMicros, long docsScored, long blocksSkipped) {}

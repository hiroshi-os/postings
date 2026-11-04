package com.postings.core.analysis;

import java.util.List;

/** Text analyzer contract for indexing and search. */
public interface Analyzer {

  /** Analyzes the given input text into a list of normalized tokens. */
  List<String> analyze(String text);
}

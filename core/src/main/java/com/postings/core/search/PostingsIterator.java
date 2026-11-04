package com.postings.core.search;

import com.postings.core.Constants;

/** Iterator over postings for a term within a segment. */
public interface PostingsIterator {

  /** Sentinel value indicating exhaustion of postings. */
  int NO_MORE_DOCS = Constants.NO_MORE_DOCS;

  /** Returns the current document ID, or {@link #NO_MORE_DOCS} if exhausted. */
  int docId();

  /**
   * Advances to the next document in the posting list.
   *
   * @return the new doc ID, or {@link #NO_MORE_DOCS} if exhausted
   */
  int nextDoc();

  /**
   * Advances to the first document with doc ID &gt;= target.
   *
   * @param target doc ID to advance to
   * @return the new doc ID &gt;= target, or {@link #NO_MORE_DOCS} if exhausted
   */
  int advance(int target);

  /** Returns the term frequency in the current document. */
  int freq();

  /** Returns the maximum BM25 term impact/score in the current block. */
  float blockMaxScore();

  /** Returns the last doc ID in the current block. */
  int blockLastDoc();

  /** Returns the global maximum BM25 term score across the entire posting list. */
  float maxScore();
}

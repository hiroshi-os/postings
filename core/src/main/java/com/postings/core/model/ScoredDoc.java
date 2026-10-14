package com.postings.core.model;

import java.util.Comparator;

/**
 * A search result hit.
 *
 * <p>Ranking order: higher score first. Ties are broken by smaller (segmentIndex, docId).
 */
public record ScoredDoc(int segmentIndex, int docId, String id, float score, String title) {

  /** Comparator for final top-k result ranking (best results first). */
  public static final Comparator<ScoredDoc> RESULT_ORDER =
      (a, b) -> {
        int cmp = Float.compare(b.score, a.score);
        if (cmp != 0) {
          return cmp;
        }
        int segCmp = Integer.compare(a.segmentIndex, b.segmentIndex);
        if (segCmp != 0) {
          return segCmp;
        }
        return Integer.compare(a.docId, b.docId);
      };

  /**
   * Comparator for a bounded min-heap of size k.
   *
   * <p>The "smallest" element according to this comparator is the worst performing hit in the heap,
   * which is candidate for eviction when a better hit arrives.
   */
  public static final Comparator<ScoredDoc> MIN_HEAP_COMPARATOR =
      (a, b) -> {
        int cmp = Float.compare(a.score, b.score);
        if (cmp != 0) {
          return cmp;
        }
        int segCmp = Integer.compare(b.segmentIndex, a.segmentIndex);
        if (segCmp != 0) {
          return segCmp;
        }
        return Integer.compare(b.docId, a.docId);
      };
}

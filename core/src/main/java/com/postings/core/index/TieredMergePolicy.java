package com.postings.core.index;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Tiered merge policy that selects similar-sized segments to merge. */
public final class TieredMergePolicy {

  public static final int DEFAULT_MAX_MERGE_AT_ONCE = 10;
  public static final int DEFAULT_SEGMENTS_PER_TIER = 10;

  private final int maxMergeAtOnce;
  private final int maxSegmentsPerTier;

  public TieredMergePolicy(int maxMergeAtOnce, int maxSegmentsPerTier) {
    this.maxMergeAtOnce = maxMergeAtOnce;
    this.maxSegmentsPerTier = maxSegmentsPerTier;
  }

  public TieredMergePolicy() {
    this(DEFAULT_MAX_MERGE_AT_ONCE, DEFAULT_SEGMENTS_PER_TIER);
  }

  /** Defines a set of segments to be merged together. */
  public record MergeSpecification(List<SegmentCommitInfo> segmentsToMerge) {}

  /**
   * Evaluates current segments and returns a merge specification if segment count exceeds tier
   * limits.
   */
  public MergeSpecification findMerges(List<SegmentCommitInfo> segments) {
    if (segments.size() <= maxSegmentsPerTier) {
      return null;
    }

    // Sort a copy by docCount ascending to merge smallest similar segments
    List<SegmentCommitInfo> sorted = new ArrayList<>(segments);
    sorted.sort(Comparator.comparingInt(SegmentCommitInfo::docCount));

    int count = Math.min(maxMergeAtOnce, sorted.size());
    List<SegmentCommitInfo> toMerge = new ArrayList<>(sorted.subList(0, count));
    return new MergeSpecification(toMerge);
  }

  /** Finds merges needed to reduce the total number of segments to &lt;= maxNumSegments. */
  public MergeSpecification findForcedMerges(List<SegmentCommitInfo> segments, int maxNumSegments) {
    if (segments.size() <= maxNumSegments) {
      return null;
    }
    return new MergeSpecification(new ArrayList<>(segments));
  }

  public int maxMergeAtOnce() {
    return maxMergeAtOnce;
  }

  public int maxSegmentsPerTier() {
    return maxSegmentsPerTier;
  }
}

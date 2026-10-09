package com.postings.core.index;

/**
 * Metadata for a committed segment in a commit point.
 *
 * @param segmentName segment prefix (e.g. "_0")
 * @param docCount total docs in segment
 * @param delGen deletion generation (0 if no deletions file, &gt;= 1 otherwise)
 * @param delCount number of deleted documents in segment
 */
public record SegmentCommitInfo(String segmentName, int docCount, int delGen, int delCount) {

  public int liveDocCount() {
    return docCount - delCount;
  }
}

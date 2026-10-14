package com.postings.core.index;

import java.io.IOException;

/** Thrown when acquiring the write lock on an index directory fails. */
public class LockObtainFailedException extends IOException {
  public LockObtainFailedException(String message) {
    super(message);
  }
}

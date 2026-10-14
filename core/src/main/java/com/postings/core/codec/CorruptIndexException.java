package com.postings.core.codec;

import java.io.IOException;

/** Thrown when an index file is malformed, truncated, or fails CRC validation. */
public class CorruptIndexException extends IOException {

  public CorruptIndexException(String message) {
    super(message);
  }

  public CorruptIndexException(String message, Throwable cause) {
    super(message, cause);
  }
}

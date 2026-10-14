package com.postings.core.model;

import java.util.Objects;

/** Document record representing an indexed entity. */
public record Document(String id, String title, String body) {

  public Document {
    Objects.requireNonNull(id, "Document id must not be null");
    title = (title == null) ? "" : title;
    body = (body == null) ? "" : body;
  }

  public Document(String id, String body) {
    this(id, "", body);
  }
}

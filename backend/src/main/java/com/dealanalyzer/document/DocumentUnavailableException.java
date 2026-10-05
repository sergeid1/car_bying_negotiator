package com.dealanalyzer.document;

/** Safe category only: never includes an object ID, key ID, content or crypto diagnostics. */
public final class DocumentUnavailableException extends RuntimeException {
  private final String code;
  public DocumentUnavailableException(String code) {
    super("The stored document is unavailable. Please retry or upload it again.");
    this.code = code;
  }
  public String code() { return code; }
}

package com.dealanalyzer.document;

public final class InvalidDocumentException extends RuntimeException {
  private final String code;

  public InvalidDocumentException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}

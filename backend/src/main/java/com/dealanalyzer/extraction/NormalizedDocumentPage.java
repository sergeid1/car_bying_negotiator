package com.dealanalyzer.extraction;

/** A normalized page image plus bounded text recovered by the trusted document parser. */
public record NormalizedDocumentPage(
    int pageNumber,
    byte[] image,
    String recognizedText,
    boolean likelyMissing,
    boolean likelyDuplicate,
    boolean likelyRotated,
    boolean likelyCropped) {
  public NormalizedDocumentPage {
    image = image.clone();
    recognizedText = recognizedText == null ? "" : recognizedText;
  }
}

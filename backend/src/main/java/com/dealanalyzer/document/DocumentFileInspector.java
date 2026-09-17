package com.dealanalyzer.document;

import org.springframework.stereotype.Component;

@Component
class DocumentFileInspector {
  private final DocumentParserProcess parser;
  DocumentFileInspector(DocumentParserProcess parser) { this.parser = parser; }

  InspectedDocument inspect(byte[] bytes) { return parser.inspect(bytes); }

  static InvalidDocumentException invalid(String code, String message) {
    return new InvalidDocumentException(code, message);
  }

  record InspectedDocument(
      String mediaType, int pageCount, Integer width, Integer height, Integer dpi,
      boolean likelyMissing, boolean likelyDuplicate, boolean likelyRotated,
      boolean likelyCropped) {}
}

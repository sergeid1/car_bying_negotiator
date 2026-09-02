package com.dealanalyzer.extraction;

import java.util.List;

/** Supplies only normalized page images to extraction; original artifacts are outside this boundary. */
public interface DocumentExtractionSource {
  ExtractionSourceDocument load(String documentId);

  record ExtractionSourceDocument(
      String caseId, String documentId, List<NormalizedDocumentPage> pages) {
    public ExtractionSourceDocument {
      pages = List.copyOf(pages);
    }
  }

}

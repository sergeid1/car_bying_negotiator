package com.dealanalyzer.document;

import com.dealanalyzer.document.persistence.DocumentIntakePersistence.SourceDocumentView;
import org.springframework.stereotype.Component;

@Component
class IsolatedDocumentPageNormalizer implements DocumentPageNormalizer {
  @Override
  public void normalize(SourceDocumentView document) {
    if (document.pages().isEmpty()) {
      throw new IllegalStateException("Document page metadata is unavailable");
    }
  }
}

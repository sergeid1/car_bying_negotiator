package com.dealanalyzer.document;

import com.dealanalyzer.document.persistence.DocumentIntakePersistence.SourceDocumentView;

public interface DocumentPageNormalizer {
  void normalize(SourceDocumentView document);
}

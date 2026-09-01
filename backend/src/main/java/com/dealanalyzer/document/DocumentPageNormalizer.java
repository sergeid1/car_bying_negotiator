package com.dealanalyzer.document;

import com.dealanalyzer.document.persistence.DocumentIntakePersistence.ProcessingDocument;

public interface DocumentPageNormalizer {
  void normalize(ProcessingDocument document);
}

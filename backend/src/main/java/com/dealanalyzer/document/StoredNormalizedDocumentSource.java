package com.dealanalyzer.document;

import com.dealanalyzer.document.persistence.DocumentIntakePersistence;
import com.dealanalyzer.extraction.DocumentExtractionSource;
import com.dealanalyzer.extraction.NormalizedDocumentPage;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class StoredNormalizedDocumentSource implements DocumentExtractionSource {
  private final DocumentIntakePersistence persistence;
  private final DocumentObjectStorage storage;
  private final DocumentParserProcess parser;

  StoredNormalizedDocumentSource(
      DocumentIntakePersistence persistence, DocumentObjectStorage storage, DocumentParserProcess parser) {
    this.persistence = persistence;
    this.storage = storage;
    this.parser = parser;
  }

  @Override
  public ExtractionSourceDocument load(String documentId) {
    DocumentIntakePersistence.ExtractionDocument document =
        persistence.requireExtractionDocument(documentId);
    List<String> pageText = recognizedText(document);
    return new ExtractionSourceDocument(
        document.caseId(), document.documentId(), document.pages().stream()
            .map(page -> new NormalizedDocumentPage(
                page.pageNumber(),
                storage.readNormalized(
                    document.caseId(), document.documentId(),
                    page.pageNumber(), page.normalizedObjectKey()),
                pageText.get(page.pageNumber() - 1),
                page.likelyMissing(), page.likelyDuplicate(),
                page.likelyRotated(), page.likelyCropped()))
            .toList());
  }

  private List<String> recognizedText(DocumentIntakePersistence.ExtractionDocument document) {
    if (!document.mediaType().equals("application/pdf")) {
      return java.util.Collections.nCopies(document.pages().size(), "");
    }
    byte[] original = storage.readOriginal(
        document.caseId(), document.documentId(), document.originalObjectKey());
    return parser.text(original, document.pages().size());
  }
}

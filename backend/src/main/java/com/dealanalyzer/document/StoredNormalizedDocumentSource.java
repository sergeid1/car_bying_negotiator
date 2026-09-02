package com.dealanalyzer.document;

import com.dealanalyzer.document.persistence.DocumentIntakePersistence;
import com.dealanalyzer.extraction.DocumentExtractionSource;
import com.dealanalyzer.extraction.NormalizedDocumentPage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

@Component
class StoredNormalizedDocumentSource implements DocumentExtractionSource {
  private final DocumentIntakePersistence persistence;
  private final DocumentObjectStorage storage;

  StoredNormalizedDocumentSource(
      DocumentIntakePersistence persistence, DocumentObjectStorage storage) {
    this.persistence = persistence;
    this.storage = storage;
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
    try (var pdf = Loader.loadPDF(original)) {
      List<String> pages = new ArrayList<>();
      for (int pageNumber = 1; pageNumber <= pdf.getNumberOfPages(); pageNumber++) {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(pageNumber);
        stripper.setEndPage(pageNumber);
        String text = stripper.getText(pdf);
        pages.add(text.length() > 100_000 ? text.substring(0, 100_000) : text);
      }
      return pages;
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to recover normalized page text", exception);
    }
  }
}

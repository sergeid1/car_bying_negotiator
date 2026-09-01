package com.dealanalyzer.document;

import com.dealanalyzer.document.persistence.DocumentIntakePersistence;
import com.dealanalyzer.document.persistence.DocumentIntakePersistence.ProcessingDocument;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class IsolatedDocumentPageNormalizer implements DocumentPageNormalizer {
  private final DocumentObjectStorage storage;
  private final DocumentIntakePersistence persistence;
  private final HardenedImageDecoder imageDecoder;
  private final int renderingDpi;
  private final long maxPagePixels;

  IsolatedDocumentPageNormalizer(
      DocumentObjectStorage storage,
      DocumentIntakePersistence persistence,
      HardenedImageDecoder imageDecoder,
      @Value("${document.normalization.dpi:144}") int renderingDpi,
      @Value("${document.normalization.max-page-pixels:40000000}") long maxPagePixels) {
    this.storage = storage;
    this.persistence = persistence;
    this.imageDecoder = imageDecoder;
    this.renderingDpi = renderingDpi;
    this.maxPagePixels = maxPagePixels;
  }

  @Override
  public void normalize(ProcessingDocument document) {
    if (!document.lifecycle().equals("ACTIVE")
        || !document.retentionDeadline().isAfter(Instant.now())) {
      throw new IllegalStateException("Document is unavailable for normalization");
    }
    deleteKnownOutputs(document.id());
    List<String> written = new ArrayList<>();
    try {
      byte[] original = storage.readOriginal(document.caseId(), document.id(), document.objectKey());
      if (document.mediaType().equals("application/pdf")) {
        normalizePdf(document, original, written);
      } else {
        BufferedImage decoded = imageDecoder.decode(original, document.mediaType());
        writePage(document, 1, decoded, renderingDpi, written);
      }
      if (written.size() != document.pageCount()) {
        throw new IllegalStateException("Normalizer produced an incomplete page set");
      }
    } catch (Exception exception) {
      written.addAll(persistence.clearNormalizedArtifacts(document.id(), Instant.now()));
      written.stream().distinct().forEach(this::deleteQuietly);
      throw exception instanceof RuntimeException runtime
          ? runtime : new IllegalStateException("Document normalization failed", exception);
    }
  }

  private void normalizePdf(
      ProcessingDocument document, byte[] original, List<String> written) throws IOException {
    try (PDDocument pdf = Loader.loadPDF(original)) {
      int pageCount = PdfPageTreeValidator.validate(pdf, document.pageCount());
      if (pageCount != document.pageCount()) {
        throw new IllegalStateException("PDF page count changed after intake validation");
      }
      PDFRenderer renderer = new PDFRenderer(pdf);
      for (int index = 0; index < pageCount; index++) {
        var mediaBox = pdf.getPage(index).getMediaBox();
        long width = Math.max(1, Math.round(mediaBox.getWidth() * renderingDpi / 72.0));
        long height = Math.max(1, Math.round(mediaBox.getHeight() * renderingDpi / 72.0));
        if (Math.multiplyExact(width, height) > maxPagePixels) {
          throw new IllegalStateException("PDF page exceeds normalized pixel limit");
        }
        BufferedImage image = renderer.renderImageWithDPI(index, renderingDpi, ImageType.RGB);
        writePage(document, index + 1, image, renderingDpi, written);
      }
    }
  }

  private void writePage(
      ProcessingDocument document, int pageNumber, BufferedImage image, int dpi,
      List<String> written) throws IOException {
    checkPixels(image);
    ByteArrayOutputStream encoded = new ByteArrayOutputStream();
    if (!ImageIO.write(image, "PNG", encoded)) {
      throw new IOException("PNG encoder is unavailable");
    }
    DocumentObjectStorage.StoredObject stored = storage.storeNormalized(
        document.caseId(), document.id(), pageNumber, encoded.toByteArray());
    written.add(stored.objectKey());
    persistence.recordNormalizedPage(
        document.id(), pageNumber, stored.objectKey(),
        image.getWidth(), image.getHeight(), dpi, Instant.now());
  }

  private void checkPixels(BufferedImage image) {
    if ((long) image.getWidth() * image.getHeight() > maxPagePixels) {
      throw new IllegalStateException("Normalized page exceeds pixel limit");
    }
  }

  private void deleteKnownOutputs(String documentId) {
    persistence.clearNormalizedArtifacts(documentId, Instant.now()).forEach(this::deleteQuietly);
  }

  private void deleteQuietly(String objectKey) {
    try {
      storage.delete(objectKey);
    } catch (RuntimeException ignored) {
      // The failed operation remains retryable and retention cleanup retries deletion.
    }
  }
}

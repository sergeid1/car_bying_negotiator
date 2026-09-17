package com.dealanalyzer.document;

import java.awt.image.BufferedImage;
import com.dealanalyzer.document.DocumentFileInspector.InspectedDocument;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

class LocalDocumentInspector {
  private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
  private final long maxFileBytes;
  private final int maxPages;
  private final long maxPixels;
  private final int minWidth;
  private final int minHeight;
  private final long maxExpansionRatio;
  private final HardenedImageDecoder imageDecoder;

  LocalDocumentInspector(
      long maxFileBytes,
      int maxPages,
      long maxPixels,
      int minWidth,
      int minHeight,
      long maxExpansionRatio,
      HardenedImageDecoder imageDecoder) {
    this.maxFileBytes = maxFileBytes;
    this.maxPages = maxPages;
    this.maxPixels = maxPixels;
    this.minWidth = minWidth;
    this.minHeight = minHeight;
    this.maxExpansionRatio = maxExpansionRatio;
    this.imageDecoder = imageDecoder;
  }

  InspectedDocument inspect(byte[] bytes) {
    if (bytes.length == 0) {
      throw invalid("EMPTY_FILE", "The uploaded document is empty. Choose a PDF or image and try again.");
    }
    if (bytes.length > maxFileBytes) {
      throw invalid("FILE_TOO_LARGE", "The document exceeds the configured file-size limit. Upload a smaller file and try again.");
    }
    if (starts(bytes, new byte[] {'M', 'Z'})
        || starts(bytes, new byte[] {0x7f, 'E', 'L', 'F'})
        || starts(bytes, new byte[] {'#', '!'})) {
      throw invalid("EXECUTABLE_FILE", "Executable files are not accepted. Upload the dealer document as PDF, JPEG, PNG, or HEIC and try again.");
    }
    if (starts(bytes, "%PDF-".getBytes(StandardCharsets.US_ASCII))) {
      return inspectPdf(bytes);
    }
    String mediaType = starts(bytes, PNG)
        ? "image/png"
        : bytes.length >= 4 && unsigned(bytes[0]) == 0xff && unsigned(bytes[1]) == 0xd8
            ? "image/jpeg"
            : isHeic(bytes) ? "image/heic" : null;
    if (mediaType != null) {
      BufferedImage image = imageDecoder.decode(bytes, mediaType);
      checkImage(image.getWidth(), image.getHeight(), bytes.length);
      boolean rotated = image.getHeight() < image.getWidth();
      double ratio = Math.max(image.getWidth(), image.getHeight())
          / (double) Math.min(image.getWidth(), image.getHeight());
      return new InspectedDocument(
          mediaType, 1, image.getWidth(), image.getHeight(), null,
          false, false, rotated, ratio > 3.0);
    }
    throw invalid("UNSUPPORTED_FILE", "The file signature is not a supported PDF, JPEG, PNG, or HEIC document. Choose a supported copy and try again.");
  }

  private InspectedDocument inspectPdf(byte[] bytes) {
    try (PDDocument document = Loader.loadPDF(bytes)) {
      if (document.isEncrypted()) {
        throw invalid("ENCRYPTED_FILE", "Encrypted or password-protected PDFs cannot be processed. Upload an unlocked copy and try again.");
      }
      int pages = PdfPageTreeValidator.validate(document, maxPages);
      // Read page content incrementally, never materializing a decompressed stream.
      long budget = Math.min(64L * 1024 * 1024, Math.multiplyExact(bytes.length, maxExpansionRatio));
      byte[] chunk = new byte[8192];
      for (var page : document.getPages()) {
        try (var contents = page.getContents()) {
          int count;
          while ((count = contents.read(chunk)) != -1) {
            budget -= count;
            if (budget < 0) throw invalid("DECOMPRESSION_RISK", "PDF content exceeds the safe expansion limit.");
          }
        }
      }
      return new InspectedDocument(
          "application/pdf", pages, null, null, null,
          false, duplicatePageContent(document), false, false);
    } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException exception) {
      throw invalid("ENCRYPTED_FILE", "Encrypted or password-protected PDFs cannot be processed. Upload an unlocked copy and try again.");
    } catch (InvalidDocumentException exception) {
      throw exception;
    } catch (IOException | RuntimeException exception) {
      throw invalid("MALFORMED_FILE", "The PDF page tree is malformed or untrustworthy. Export it again and retry.");
    }
  }

  private static boolean duplicatePageContent(PDDocument document) throws IOException {
    java.util.Set<String> streams = new java.util.HashSet<>();
    for (org.apache.pdfbox.pdmodel.PDPage page : document.getPages()) {
      java.util.Iterator<org.apache.pdfbox.pdmodel.common.PDStream> contents = page.getContentStreams();
      while (contents.hasNext()) {
        String identity = contents.next().getCOSObject().toString();
        if (!streams.add(identity)) {
          return true;
        }
      }
    }
    return false;
  }

  private void checkImage(int width, int height, int compressedBytes) {
    long pixels = (long) width * height;
    if (width < minWidth || height < minHeight) {
      throw invalid("RESOLUTION_TOO_LOW", "The image resolution is below the configured limit. Upload a clearer photo and try again.");
    }
    if (pixels > maxPixels) {
      throw invalid("PIXEL_LIMIT_EXCEEDED", "The image exceeds the configured pixel limit. Resize it and try again.");
    }
    long expandedBytes = pixels * 4L;
    if (compressedBytes > 0 && expandedBytes / compressedBytes > maxExpansionRatio) {
      throw invalid("DECOMPRESSION_RISK", "The image expands beyond the safe processing limit. Resize or re-export it and try again.");
    }
  }

  private static boolean isHeic(byte[] bytes) {
    if (bytes.length < 12 || !ascii(bytes, 4, "ftyp")) {
      return false;
    }
    String brand = new String(bytes, 8, 4, StandardCharsets.US_ASCII);
    return brand.equals("heic") || brand.equals("heix") || brand.equals("hevc")
        || brand.equals("hevx") || brand.equals("mif1") || brand.equals("msf1");
  }

  private static boolean starts(byte[] bytes, byte[] prefix) {
    if (bytes.length < prefix.length) {
      return false;
    }
    for (int index = 0; index < prefix.length; index++) {
      if (bytes[index] != prefix[index]) {
        return false;
      }
    }
    return true;
  }

  private static boolean ascii(byte[] bytes, int offset, String value) {
    return offset >= 0 && offset + value.length() <= bytes.length
        && new String(bytes, offset, value.length(), StandardCharsets.US_ASCII).equals(value);
  }

  private static int unsigned(byte value) {
    return value & 0xff;
  }

  static InvalidDocumentException invalid(String code, String message) {
    return new InvalidDocumentException(code, message);
  }

}

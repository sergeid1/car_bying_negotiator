package com.dealanalyzer.document;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class DocumentFileInspector {
  private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
  private static final Pattern PDF_PAGE = Pattern.compile("/Type\\s*/Page(?!s)");
  private static final Pattern PDF_DECLARED_COUNT = Pattern.compile("/Count\\s+(\\d+)");
  private static final Pattern PDF_CONTENT_REFERENCE =
      Pattern.compile("/Contents\\s+(\\d+\\s+\\d+\\s+R)");

  private final long maxFileBytes;
  private final int maxPages;
  private final long maxPixels;
  private final int minWidth;
  private final int minHeight;
  private final long maxExpansionRatio;

  DocumentFileInspector(
      @Value("${document.limits.max-file-bytes:20971520}") long maxFileBytes,
      @Value("${document.limits.max-pages:25}") int maxPages,
      @Value("${document.limits.max-pixels:40000000}") long maxPixels,
      @Value("${document.limits.min-width:1}") int minWidth,
      @Value("${document.limits.min-height:1}") int minHeight,
      @Value("${document.limits.max-expansion-ratio:10000}") long maxExpansionRatio) {
    this.maxFileBytes = maxFileBytes;
    this.maxPages = maxPages;
    this.maxPixels = maxPixels;
    this.minWidth = minWidth;
    this.minHeight = minHeight;
    this.maxExpansionRatio = maxExpansionRatio;
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
    if (starts(bytes, PNG)) {
      return inspectPng(bytes);
    }
    if (bytes.length >= 4 && unsigned(bytes[0]) == 0xff && unsigned(bytes[1]) == 0xd8) {
      return inspectJpeg(bytes);
    }
    if (isHeic(bytes)) {
      return inspectHeic(bytes);
    }
      throw invalid("UNSUPPORTED_FILE", "The file signature is not a supported PDF, JPEG, PNG, or HEIC document. Choose a supported copy and try again.");
  }

  private InspectedDocument inspectPdf(byte[] bytes) {
    String content = new String(bytes, StandardCharsets.ISO_8859_1);
    if (!content.contains("%%EOF")) {
      throw invalid("MALFORMED_FILE", "The PDF is incomplete or malformed. Export it again and retry.");
    }
    if (content.contains("/Encrypt")) {
      throw invalid("ENCRYPTED_FILE", "Encrypted or password-protected PDFs cannot be processed. Upload an unlocked copy and try again.");
    }
    int pages = (int) PDF_PAGE.matcher(content).results().count();
    if (pages == 0) {
      throw invalid("MALFORMED_FILE", "The PDF does not contain readable pages. Export it again and retry.");
    }
    checkPages(pages);
    int declared = declaredPageCount(content, pages);
    boolean likelyMissing = declared > pages;
    boolean likelyDuplicate = duplicatePageContent(content);
    return new InspectedDocument(
        "application/pdf", pages, null, null, null,
        likelyMissing, likelyDuplicate, false, false);
  }

  private InspectedDocument inspectPng(byte[] bytes) {
    if (bytes.length < 33 || !ascii(bytes, 12, "IHDR") || !contains(bytes, "IEND".getBytes(StandardCharsets.US_ASCII))) {
      throw invalid("MALFORMED_FILE", "The PNG is incomplete or malformed. Save a new copy and retry.");
    }
    int width = positiveInt(bytes, 16);
    int height = positiveInt(bytes, 20);
    checkImage(width, height, bytes.length);
    return image("image/png", width, height, readPngDpi(bytes));
  }

  private InspectedDocument inspectJpeg(byte[] bytes) {
    if (bytes.length < 4 || unsigned(bytes[bytes.length - 2]) != 0xff
        || unsigned(bytes[bytes.length - 1]) != 0xd9) {
      throw invalid("MALFORMED_FILE", "The JPEG is incomplete or malformed. Save a new copy and retry.");
    }
    int cursor = 2;
    while (cursor + 8 < bytes.length) {
      if (unsigned(bytes[cursor]) != 0xff) {
        cursor++;
        continue;
      }
      int marker = unsigned(bytes[cursor + 1]);
      if (marker == 0xc0 || marker == 0xc1 || marker == 0xc2) {
        int height = unsignedShort(bytes, cursor + 5);
        int width = unsignedShort(bytes, cursor + 7);
        checkImage(width, height, bytes.length);
        return image("image/jpeg", width, height, null);
      }
      if (cursor + 3 >= bytes.length) {
        break;
      }
      int segmentLength = unsignedShort(bytes, cursor + 2);
      if (segmentLength < 2) {
        break;
      }
      cursor += 2 + segmentLength;
    }
    throw invalid("MALFORMED_FILE", "The JPEG has no valid image dimensions. Save a new copy and retry.");
  }

  private InspectedDocument inspectHeic(byte[] bytes) {
    int ispe = indexOf(bytes, "ispe".getBytes(StandardCharsets.US_ASCII));
    if (ispe < 0 || ispe + 16 > bytes.length) {
      throw invalid("MALFORMED_FILE", "The HEIC has no readable image dimensions. Export it again and retry.");
    }
    int width = positiveInt(bytes, ispe + 8);
    int height = positiveInt(bytes, ispe + 12);
    checkImage(width, height, bytes.length);
    return image("image/heic", width, height, null);
  }

  private InspectedDocument image(String mediaType, int width, int height, Integer dpi) {
    boolean likelyRotated = height < width;
    double ratio = Math.max(width, height) / (double) Math.min(width, height);
    boolean likelyCropped = ratio > 3.0;
    return new InspectedDocument(
        mediaType, 1, width, height, dpi, false, false, likelyRotated, likelyCropped);
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

  private void checkPages(int pages) {
    if (pages > maxPages) {
      throw invalid("PAGE_LIMIT_EXCEEDED", "The PDF exceeds the configured page limit. Split it into smaller documents and try again.");
    }
  }

  private static int declaredPageCount(String content, int actual) {
    Matcher matcher = PDF_DECLARED_COUNT.matcher(content);
    int largest = actual;
    while (matcher.find()) {
      largest = Math.max(largest, Integer.parseInt(matcher.group(1)));
    }
    return largest;
  }

  private static boolean duplicatePageContent(String content) {
    Matcher matcher = PDF_CONTENT_REFERENCE.matcher(content);
    java.util.Set<String> references = new java.util.HashSet<>();
    while (matcher.find()) {
      if (!references.add(matcher.group(1))) {
        return true;
      }
    }
    return false;
  }

  private static Integer readPngDpi(byte[] bytes) {
    int phys = indexOf(bytes, "pHYs".getBytes(StandardCharsets.US_ASCII));
    if (phys < 0 || phys + 13 > bytes.length || bytes[phys + 12] != 1) {
      return null;
    }
    int pixelsPerMeter = positiveInt(bytes, phys + 4);
    return Math.max(1, Math.round(pixelsPerMeter * 0.0254f));
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

  private static boolean contains(byte[] bytes, byte[] sought) {
    return indexOf(bytes, sought) >= 0;
  }

  private static int indexOf(byte[] bytes, byte[] sought) {
    outer:
    for (int index = 0; index <= bytes.length - sought.length; index++) {
      for (int offset = 0; offset < sought.length; offset++) {
        if (bytes[index + offset] != sought[offset]) {
          continue outer;
        }
      }
      return index;
    }
    return -1;
  }

  private static boolean ascii(byte[] bytes, int offset, String value) {
    return offset >= 0 && offset + value.length() <= bytes.length
        && new String(bytes, offset, value.length(), StandardCharsets.US_ASCII).equals(value);
  }

  private static int positiveInt(byte[] bytes, int offset) {
    long value = Integer.toUnsignedLong(ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).getInt());
    if (value == 0 || value > Integer.MAX_VALUE) {
      throw invalid("MALFORMED_FILE", "The image contains invalid dimensions. Save a new copy and retry.");
    }
    return (int) value;
  }

  private static int unsignedShort(byte[] bytes, int offset) {
    return (unsigned(bytes[offset]) << 8) | unsigned(bytes[offset + 1]);
  }

  private static int unsigned(byte value) {
    return value & 0xff;
  }

  private static InvalidDocumentException invalid(String code, String message) {
    return new InvalidDocumentException(code, message);
  }

  record InspectedDocument(
      String mediaType,
      int pageCount,
      Integer width,
      Integer height,
      Integer dpi,
      boolean likelyMissing,
      boolean likelyDuplicate,
      boolean likelyRotated,
      boolean likelyCropped) {}
}

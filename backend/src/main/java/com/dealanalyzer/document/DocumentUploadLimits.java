package com.dealanalyzer.document;

import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;

/**
 * The upload limits a client may mirror so a Buyer standing at a dealership learns about a refusal
 * before sending bytes over a phone connection. Publishing the configured values keeps a client
 * pre-check from inventing a looser threshold than the one intake actually enforces; the server
 * remains the sole authority on acceptance.
 */
@Service
public class DocumentUploadLimits {
  private final View view;

  DocumentUploadLimits(Environment environment) {
    long configured = environment.getProperty(
        "document.limits.max-file-bytes", Long.class, (long) DocumentParserProcess.MAX_INPUT_BYTES);
    DataSize multipart = environment.getProperty(
        "spring.servlet.multipart.max-file-size", DataSize.class);
    // A request over the multipart ceiling is refused before intake sees it, so the smaller wins.
    long maxFileBytes = multipart == null || multipart.toBytes() < 0
        ? configured
        : Math.min(configured, multipart.toBytes());
    this.view = new View(
        DocumentParserProcess.SUPPORTED_MEDIA_TYPES.stream().sorted().toList(),
        maxFileBytes,
        environment.getProperty("document.limits.max-pages", Integer.class, 25),
        environment.getProperty("document.limits.max-pixels", Long.class, 40_000_000L),
        environment.getProperty("document.limits.min-width", Integer.class, 1),
        environment.getProperty("document.limits.min-height", Integer.class, 1));
  }

  public View current() {
    return view;
  }

  public record View(
      List<String> acceptedMediaTypes, long maxFileBytes, int maxPages, long maxPixels,
      int minWidth, int minHeight) {}
}

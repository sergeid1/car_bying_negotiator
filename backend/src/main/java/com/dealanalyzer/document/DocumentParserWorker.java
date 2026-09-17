package com.dealanalyzer.document;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

/** Disposable entry point. Never starts Spring or receives database/storage credentials. */
public final class DocumentParserWorker {
  private DocumentParserWorker() {}

  public static void main(String[] args) throws Exception {
    var out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(FileDescriptor.out)));
    System.setOut(new PrintStream(OutputStream.nullOutputStream()));
    ImageIO.setUseCache(false);
    try {
      var input = new DataInputStream(new BufferedInputStream(System.in));
      String operation = input.readUTF();
      var settings = DocumentParserProcess.Settings.read(input);
      int page = input.readInt();
      byte[] bytes = DocumentParserProcess.readBytes(input, settings.maxFileBytes());
      var decoder = new HardenedImageDecoder(settings.heicCommand(), Duration.ofMillis(settings.decoderTimeoutMillis()));
      var inspector = new LocalDocumentInspector(settings.maxFileBytes(), settings.maxPages(), settings.maxPixels(),
          settings.minWidth(), settings.minHeight(), settings.expansionRatio(), decoder);
      var document = inspector.inspect(bytes);
      switch (operation) {
        case "inspect" -> {
          out.writeByte(0); out.writeUTF(document.mediaType()); out.writeInt(document.pageCount());
          out.writeInt(document.width() == null ? 0 : document.width());
          out.writeInt(document.height() == null ? 0 : document.height());
          out.writeBoolean(document.likelyDuplicate()); out.writeBoolean(document.likelyRotated()); out.writeBoolean(document.likelyCropped());
        }
        case "render" -> {
          if (page < 1 || page > document.pageCount()) throw new IOException("Invalid page");
          BufferedImage image;
          if (document.mediaType().equals("application/pdf")) {
            try (var pdf = Loader.loadPDF(bytes)) {
              var box = pdf.getPage(page - 1).getCropBox();
              double width = Math.ceil(box.getWidth() * settings.dpi() / 72.0);
              double height = Math.ceil(box.getHeight() * settings.dpi() / 72.0);
              if (!Double.isFinite(width * height) || width <= 0 || height <= 0
                  || width * height > settings.maxPagePixels()) throw DocumentParserProcess.rejected("PIXEL_LIMIT_EXCEEDED");
              image = new PDFRenderer(pdf).renderImageWithDPI(page - 1, settings.dpi(), ImageType.RGB);
            }
          } else image = decoder.decode(bytes, document.mediaType());
          if ((long) image.getWidth() * image.getHeight() > settings.maxPagePixels()) throw DocumentParserProcess.rejected("PIXEL_LIMIT_EXCEEDED");
          var encoded = new BoundedOutput(DocumentParserProcess.MAX_PAGE_BYTES);
          if (!ImageIO.write(image, "PNG", encoded)) throw new IOException("PNG encoder unavailable");
          out.writeByte(0); out.writeInt(image.getWidth()); out.writeInt(image.getHeight());
          out.writeInt(encoded.size()); encoded.writeTo(out);
        }
        case "text" -> {
          if (!document.mediaType().equals("application/pdf") || page != document.pageCount()) throw new IOException("Invalid text request");
          try (var pdf = Loader.loadPDF(bytes)) {
            out.writeByte(0); out.writeInt(document.pageCount());
            for (int number = 1; number <= document.pageCount(); number++) {
              var stripper = new PDFTextStripper(); stripper.setStartPage(number); stripper.setEndPage(number);
              var text = new BoundedText(); stripper.writeText(pdf, text);
              byte[] utf8 = text.value.toString().getBytes(StandardCharsets.UTF_8);
              out.writeInt(utf8.length); out.write(utf8);
            }
          }
        }
        default -> throw new IOException("Invalid operation");
      }
      out.flush();
    } catch (Exception failure) {
      out.writeByte(1);
      out.writeUTF(failure instanceof InvalidDocumentException invalid ? invalid.code() : "MALFORMED_FILE");
      out.flush();
    }
  }

  private static final class BoundedText extends Writer {
    final StringBuilder value = new StringBuilder();
    @Override public void write(char[] chars, int offset, int length) throws IOException {
      if ((long) value.length() + length > 100_000) throw new IOException("Text limit exceeded");
      value.append(chars, offset, length);
    }
    @Override public void flush() {}
    @Override public void close() {}
  }

  private static final class BoundedOutput extends ByteArrayOutputStream {
    private final int limit;
    BoundedOutput(int limit) { this.limit = limit; }
    @Override public synchronized void write(byte[] bytes, int offset, int length) {
      check(length); super.write(bytes, offset, length);
    }
    @Override public synchronized void write(int value) { check(1); super.write(value); }
    private void check(int length) {
      if ((long) count + length > limit) throw DocumentParserProcess.rejected("DECOMPRESSION_RISK");
    }
  }
}

package com.dealanalyzer.document;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class HardenedImageDecoder {
  private final String heicDecoderCommand;
  private final Duration timeout;

  HardenedImageDecoder(
      @Value("${document.heic.decoder-command:heif-dec}") String heicDecoderCommand,
      @Value("${document.decoder.timeout:PT10S}") Duration timeout) {
    this.heicDecoderCommand = heicDecoderCommand;
    this.timeout = timeout;
  }

  BufferedImage decode(byte[] bytes, String mediaType) {
    if (mediaType.equals("image/png")) {
      validatePng(bytes);
    } else if (mediaType.equals("image/jpeg")) {
      validateJpegStructure(bytes);
    } else if (mediaType.equals("image/heic")) {
      return decodeHeic(bytes);
    }
    try {
      BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
      if (image == null) {
        throw malformed(mediaType);
      }
      image.getRGB(0, 0);
      return image;
    } catch (IOException | RuntimeException exception) {
      if (exception instanceof InvalidDocumentException invalid) {
        throw invalid;
      }
      throw malformed(mediaType);
    }
  }

  private BufferedImage decodeHeic(byte[] bytes) {
    Path directory = null;
    try {
      directory = Files.createTempDirectory("dealer-document-heic-");
      Path input = directory.resolve("input.heic");
      Path output = directory.resolve("output.png");
      Files.write(input, bytes);
      Process process = new ProcessBuilder(
          heicDecoderCommand, input.toString(), output.toString())
          .redirectErrorStream(true)
          .redirectOutput(ProcessBuilder.Redirect.DISCARD)
          .start();
      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        throw malformed("image/heic");
      }
      if (process.exitValue() != 0 || !Files.isRegularFile(output)) {
        throw malformed("image/heic");
      }
      BufferedImage image = ImageIO.read(output.toFile());
      if (image == null) {
        throw malformed("image/heic");
      }
      image.getRGB(0, 0);
      return image;
    } catch (IOException exception) {
      throw malformed("image/heic");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw malformed("image/heic");
    } finally {
      if (directory != null) {
        try (var paths = Files.walk(directory)) {
          paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
            try {
              Files.deleteIfExists(path);
            } catch (IOException ignored) {
              // The isolated worker's private temporary files are removed best-effort.
            }
          });
        } catch (IOException ignored) {
          // The operating system also reclaims its temporary directory.
        }
      }
    }
  }

  private static void validatePng(byte[] bytes) {
    int cursor = 8;
    boolean ihdr = false;
    boolean idat = false;
    boolean iend = false;
    while (cursor < bytes.length) {
      if (bytes.length - cursor < 12) {
        throw malformed("image/png");
      }
      long unsignedLength = Integer.toUnsignedLong(readInt(bytes, cursor));
      if (unsignedLength > Integer.MAX_VALUE) {
        throw malformed("image/png");
      }
      int length = (int) unsignedLength;
      long end = (long) cursor + 12L + length;
      if (end > bytes.length) {
        throw malformed("image/png");
      }
      String type = new String(bytes, cursor + 4, 4, StandardCharsets.US_ASCII);
      CRC32 crc = new CRC32();
      crc.update(bytes, cursor + 4, length + 4);
      long expected = Integer.toUnsignedLong(readInt(bytes, cursor + 8 + length));
      if (crc.getValue() != expected) {
        throw malformed("image/png");
      }
      if (!ihdr && (!type.equals("IHDR") || length != 13)) {
        throw malformed("image/png");
      }
      if (type.equals("IHDR")) {
        if (ihdr) {
          throw malformed("image/png");
        }
        ihdr = true;
      } else if (type.equals("IDAT")) {
        idat = true;
      } else if (type.equals("IEND")) {
        if (length != 0 || end != bytes.length) {
          throw malformed("image/png");
        }
        iend = true;
      }
      cursor = Math.toIntExact(end);
    }
    if (!ihdr || !idat || !iend) {
      throw malformed("image/png");
    }
  }

  private static void validateJpegStructure(byte[] bytes) {
    if (bytes.length < 4 || unsigned(bytes[bytes.length - 2]) != 0xff
        || unsigned(bytes[bytes.length - 1]) != 0xd9) {
      throw malformed("image/jpeg");
    }
    int cursor = 2;
    boolean scan = false;
    while (cursor < bytes.length - 2) {
      if (unsigned(bytes[cursor]) != 0xff) {
        if (!scan) {
          throw malformed("image/jpeg");
        }
        cursor++;
        continue;
      }
      while (cursor < bytes.length && unsigned(bytes[cursor]) == 0xff) {
        cursor++;
      }
      if (cursor >= bytes.length) {
        throw malformed("image/jpeg");
      }
      int marker = unsigned(bytes[cursor++]);
      if (marker == 0x00 || marker == 0xd8 || marker == 0xd9 || marker >= 0xd0 && marker <= 0xd7) {
        continue;
      }
      if (cursor + 2 > bytes.length) {
        throw malformed("image/jpeg");
      }
      int length = (unsigned(bytes[cursor]) << 8) | unsigned(bytes[cursor + 1]);
      if (length < 2 || (long) cursor + length > bytes.length) {
        throw malformed("image/jpeg");
      }
      if (marker == 0xda) {
        scan = true;
        cursor += length;
        break;
      }
      cursor += length;
    }
    if (!scan) {
      throw malformed("image/jpeg");
    }
  }

  private static int readInt(byte[] bytes, int offset) {
    return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).getInt();
  }

  private static int unsigned(byte value) {
    return value & 0xff;
  }

  private static InvalidDocumentException malformed(String mediaType) {
    String kind = switch (mediaType) {
      case "image/png" -> "PNG";
      case "image/jpeg" -> "JPEG";
      default -> "HEIC";
    };
    return DocumentFileInspector.invalid(
        "MALFORMED_FILE",
        "The " + kind + " structure or pixels could not be decoded safely. Save a new copy and retry.");
  }
}

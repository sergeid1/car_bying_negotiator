package com.dealanalyzer.document;

import static org.assertj.core.api.Assertions.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.zip.*;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class DocumentParserProcessTest {
  @TempDir Path directory;

  @Test void compressedPdfContentIsRejectedBeforeRenderingAndNextRequestStillWorks() throws Exception {
    var environment = new MockEnvironment().withProperty("document.limits.max-expansion-ratio", "10");
    var parser = new DocumentParserProcess(environment);
    try {
      byte[] pdf;
      try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
        var page = new PDPage(); document.addPage(page);
        page.setContents(new PDStream(document, new ByteArrayInputStream(new byte[1024 * 1024]), COSName.FLATE_DECODE));
        document.save(output); pdf = output.toByteArray();
      }
      assertRejected(() -> parser.inspect(pdf), "DECOMPRESSION_RISK");
      assertThat(parser.inspect(png(2, 2)).width()).isEqualTo(2);
    } finally { parser.close(); }
  }

  @Test void heapExhaustionIsContainedInChildAndDoesNotPoisonLaterRequests() throws Exception {
    var parser = new DocumentParserProcess(DocumentParserProcess.Settings.from(new MockEnvironment()),
        Duration.ofSeconds(10), 64, 1);
    try {
      byte[] bomb = png(5000, 5000); // 100 MB decoded pixels, produced without allocating them here.
      assertRejected(() -> parser.inspect(bomb), "MALFORMED_FILE");
      assertThat(parser.inspect(png(2, 2)).height()).isEqualTo(2);
    } finally { parser.close(); }
  }

  @Test void timeoutKillsNativeDescendantAndSaturationDoesNotQueueAnotherDocument() throws Exception {
    Path pidFile = directory.resolve("decoder.pid");
    Path script = directory.resolve("decoder.sh");
    Files.writeString(script, "#!/bin/sh\necho $$ > '" + pidFile + "'\nwhile :; do :; done\n");
    assertThat(script.toFile().setExecutable(true)).isTrue();
    var settings = DocumentParserProcess.Settings.from(new MockEnvironment()
        .withProperty("document.heic.decoder-command", script.toString()));
    var parser = new DocumentParserProcess(settings, Duration.ofSeconds(3), 128, 1);
    try (var callers = Executors.newSingleThreadExecutor()) {
      var first = callers.submit(() -> {
        assertRejected(() -> parser.inspect(heic()), "PARSER_TIMEOUT");
      });
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (!Files.exists(pidFile) && System.nanoTime() < deadline) Thread.sleep(10);
      assertThat(Files.exists(pidFile)).isTrue();
      assertRejected(() -> parser.inspect(png(2, 2)), "PARSER_BUSY");
      first.get(5, TimeUnit.SECONDS);
      long pid = Long.parseLong(Files.readString(pidFile).trim());
      deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) Thread.sleep(10);
      assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
      assertThat(parser.inspect(png(2, 2)).mediaType()).isEqualTo("image/png");
    } finally { parser.close(); }
  }

  @Test void workerReceivesNoApplicationEnvironment() throws Exception {
    Path output = directory.resolve("environment.txt");
    Path image = directory.resolve("decoded.png"); Files.write(image, png(2, 2));
    Path script = directory.resolve("decoder.sh");
    Files.writeString(script, "#!/bin/sh\n/usr/bin/env > '" + output + "'\n/bin/cp '" + image + "' \"$2\"\n");
    assertThat(script.toFile().setExecutable(true)).isTrue();
    var parser = new DocumentParserProcess(new MockEnvironment().withProperty("document.heic.decoder-command", script.toString()));
    try {
      assertThat(parser.inspect(heic()).mediaType()).isEqualTo("image/heic");
      assertThat(Files.readAllLines(output)).allMatch(line -> line.startsWith("PWD=") || line.startsWith("SHLVL=") || line.startsWith("_=") || line.startsWith("__CF_USER_TEXT_ENCODING="));
    } finally { parser.close(); }
  }

  private static void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
    assertThatThrownBy(action).isInstanceOfSatisfying(InvalidDocumentException.class,
        failure -> assertThat(failure.code()).isEqualTo(code));
  }

  private static byte[] heic() {
    return ByteBuffer.allocate(24).putInt(24).put("ftypheic".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
        .putInt(0).put("heic".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(0).array();
  }

  static byte[] png(int width, int height) throws IOException {
    var output = new ByteArrayOutputStream();
    output.write(new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10});
    chunk(output, "IHDR", ByteBuffer.allocate(13).putInt(width).putInt(height)
        .put((byte) 8).put((byte) 6).put(new byte[3]).array());
    var compressed = new ByteArrayOutputStream();
    try (var deflater = new DeflaterOutputStream(compressed)) {
      byte[] row = new byte[width * 4 + 1];
      for (int i = 0; i < height; i++) deflater.write(row);
    }
    chunk(output, "IDAT", compressed.toByteArray()); chunk(output, "IEND", new byte[0]);
    return output.toByteArray();
  }

  private static void chunk(OutputStream output, String name, byte[] data) throws IOException {
    var writer = new DataOutputStream(output);
    byte[] type = name.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    CRC32 crc = new CRC32(); crc.update(type); crc.update(data);
    writer.writeInt(data.length); writer.write(type); writer.write(data); writer.writeInt((int) crc.getValue());
  }
}

package com.dealanalyzer.document;

import static org.assertj.core.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.mock.env.MockEnvironment;

/** Explicit release gate: run against the just-built immutable parser image. */
@EnabledIfSystemProperty(named = "parser.test.image", matches = "sha256:[0-9a-f]{64}")
class ContainerParserIT {
  private MockEnvironment environment(String image) {
    var environment = new MockEnvironment()
        .withProperty("document.parser.mode", "container")
        .withProperty("document.parser.container.executable", System.getProperty("parser.test.docker", "/usr/bin/docker"))
        .withProperty("document.parser.container.endpoint", System.getProperty("parser.test.endpoint", "unix:///var/run/docker.sock"))
        .withProperty("document.parser.container.image", image);
    environment.setConversionService(new org.springframework.boot.convert.ApplicationConversionService());
    return environment;
  }

  @Test void applicationDispatchInspectsRendersAndExtractsThroughConfinedRuntime() throws Exception {
    var environment = environment(System.getProperty("parser.test.image"));
    environment.setActiveProfiles("pilot");
    var parser = new DocumentParserProcess(environment);
    try {
      assertThat(parser.inspect(DocumentParserProcessTest.png(2, 2)).width()).isEqualTo(2);
      try (var fixture = getClass().getResourceAsStream("/synthetic-2x2.heic")) {
        assertThat(fixture).isNotNull();
        byte[] heic = fixture.readAllBytes();
        assertThat(parser.inspect(heic).mediaType()).isEqualTo("image/heic");
        assertThat(parser.render(heic, 1).png()).isNotEmpty();
      }
      byte[] pdf;
      try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
        document.addPage(new PDPage()); document.save(output); pdf = output.toByteArray();
      }
      assertThat(parser.inspect(pdf).pageCount()).isEqualTo(1);
      assertThat(parser.render(pdf, 1).png()).isNotEmpty();
      assertThat(parser.text(pdf, 1)).isEqualTo(List.of(""));
      assertThatThrownBy(() -> parser.inspect(new byte[] {1, 2, 3}))
          .isInstanceOf(InvalidDocumentException.class);
      assertThat(parser.inspect(DocumentParserProcessTest.png(2, 2)).height()).isEqualTo(2);
    } finally { parser.close(); }
  }

  @Test
  @EnabledIfSystemProperty(named = "parser.probe.image", matches = "sha256:[0-9a-f]{64}")
  void nativeDecoderIsSubjectToFilesystemNetworkAndAggregateResourceRestrictions() {
    var parser = new DocumentParserProcess(environment(System.getProperty("parser.probe.image"))
        .withProperty("document.heic.decoder-command", "/app/confinement-probe"));
    byte[] heic = java.nio.ByteBuffer.allocate(24).putInt(24)
        .put("ftypheic".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(0)
        .put("heic".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(0).array();
    try {
      assertThat(parser.inspect(heic).mediaType()).isEqualTo("image/heic");
      assertThat(parser.inspect(DocumentParserProcessTest.png(2, 2)).height()).isEqualTo(2);
    } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    finally { parser.close(); }
  }

  @Test
  @EnabledIfSystemProperty(named = "parser.probe.image", matches = "sha256:[0-9a-f]{64}")
  void nativeTimeoutRemovesContainerAndAllowsNextRequest() throws Exception {
    var parser = new DocumentParserProcess(environment(System.getProperty("parser.probe.image"))
        .withProperty("document.parser.timeout", "PT5S")
        .withProperty("document.parser.max-concurrent", "1")
        .withProperty("document.heic.decoder-command", "/app/confinement-probe"));
    byte[] heic = java.nio.ByteBuffer.allocate(24).putInt(24)
        .put("ftypheic".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(0)
        .put("heic".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(1).array();
    try {
      assertThatThrownBy(() -> parser.inspect(heic)).isInstanceOf(InvalidDocumentException.class);
      assertThat(parser.inspect(DocumentParserProcessTest.png(2, 2)).height()).isEqualTo(2);
    } finally { parser.close(); }
  }
}

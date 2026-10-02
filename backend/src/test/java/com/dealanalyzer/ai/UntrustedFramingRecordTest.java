package com.dealanalyzer.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.dealanalyzer.extraction.ClaudeDealTermsExtractionProvider;
import com.dealanalyzer.extraction.DealTermsExtractionProvider.ExtractionDocument;
import com.dealanalyzer.extraction.NormalizedDocumentPage;
import com.dealanalyzer.operations.ProviderMeter;
import com.dealanalyzer.operations.RecordingUsageStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code ai/untrusted-framing.v1.json} publishes the framing for the offline evaluation harness, so a
 * batch request matches a service request. The Java constants are the source of truth; this test fails
 * when the published file no longer matches them or what the gateways put on the wire.
 */
class UntrustedFramingRecordTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String RECORD = "/ai/untrusted-framing.v1.json";

  private static JsonNode record() throws Exception {
    try (InputStream source = UntrustedFramingRecordTest.class.getResourceAsStream(RECORD)) {
      return JSON.readTree(source);
    }
  }

  private static String marker(JsonNode record, String label) {
    return record.get("imageMarkerTemplate").asText().replace("{label}", label);
  }

  private static String pageLabel(JsonNode record, int pageNumber) {
    return record.get("pageImageLabelTemplate").asText().replace("{pageNumber}", String.valueOf(pageNumber));
  }

  @Test void thePublishedFramingMatchesTheConstants() throws Exception {
    JsonNode record = record();
    assertThat(record.properties()).extracting(java.util.Map.Entry::getKey)
        .containsExactly("version", "policy", "imageMarkerTemplate", "pageImageLabelTemplate");
    assertThat(record.get("version").asText()).isEqualTo("untrusted-framing.v1");
    assertThat(record.get("policy").asText()).isEqualTo(UntrustedFraming.POLICY);
    assertThat(UntrustedFraming.instructions("PROMPT")).isEqualTo("PROMPT" + record.get("policy").asText());
    for (String label : List.of("page 1 image", "page 12 image", "sample label")) {
      assertThat(marker(record, label))
          .isEqualTo(UntrustedFraming.imageMarker(new UntrustedInput.PngImage(label, new byte[] {1})));
    }
    for (int page : List.of(1, 2, 25)) {
      assertThat(pageLabel(record, page)).isEqualTo(ClaudeDealTermsExtractionProvider.pageLabel(page));
    }
  }

  /** Each gateway, driven by the real extraction provider against a loopback stub, sends what the file says. */
  @Test void thePublishedFramingMatchesWhatEachGatewaySends() throws Exception {
    JsonNode record = record();
    String prompt;
    try (InputStream source = UntrustedFramingRecordTest.class.getResourceAsStream("/ai/extraction/deal-terms-prompt.v1.md")) {
      prompt = new String(source.readAllBytes(), StandardCharsets.UTF_8).strip();
    }
    var document = new ExtractionDocument("case-1", "document-1", List.of(
        new NormalizedDocumentPage(1, new byte[] {1}, "not sent", false, false, false, false),
        new NormalizedDocumentPage(2, new byte[] {2}, "not sent", false, false, false, false)));
    String output = "{\"expectedPageCount\":2,\"candidates\":[],\"lineItems\":[],\"documentWarnings\":[]}";
    for (ModelProvider provider : ModelProvider.values()) {
      var settings = new ModelSettings(true, provider, provider.defaultModel(), "pilot-parameters.v1", "corpus.v1");
      var meter = new ProviderMeter(new RecordingUsageStore(), Duration.ofSeconds(2), 1);
      try (var stub = new StubModelServer()) {
        stub.reply(200, StubModelServer.completed(provider, provider.defaultModel(), output, 10, 10));
        var harness = new ModelHarness(meter, settings, stub.gateway(provider, Duration.ofSeconds(2)), "agreement", JSON);
        new ClaudeDealTermsExtractionProvider(harness, provider.defaultModel(), 0.85).extract(document);

        JsonNode sent = stub.requests().getFirst();
        boolean openAi = provider == ModelProvider.OPENAI;
        String system = openAi ? sent.path("instructions").asText() : sent.path("system").asText();
        JsonNode content = openAi ? sent.at("/input/0/content") : sent.at("/messages/0/content");
        assertThat(system).as(provider + " system text").isEqualTo(prompt + record.get("policy").asText());
        assertThat(content).as(provider + ": a marker before each page image, nothing else").hasSize(4);
        for (int page = 1; page <= 2; page++) {
          JsonNode text = content.get((page - 1) * 2);
          JsonNode image = content.get((page - 1) * 2 + 1);
          assertThat(text.path("text").asText()).as(provider + " marker")
              .isEqualTo(marker(record, pageLabel(record, page)));
          assertThat(image.path("type").asText()).isIn("image", "input_image");
        }
      } finally {
        meter.close();
      }
    }
  }
}

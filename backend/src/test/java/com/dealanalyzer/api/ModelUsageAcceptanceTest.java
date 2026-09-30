package com.dealanalyzer.api;

import static com.dealanalyzer.ai.StubModelServer.message;
import static com.dealanalyzer.api.HttpAcceptanceHarness.post;
import static com.dealanalyzer.api.ServiceOperationsAcceptanceTest.SETTINGS;
import static com.dealanalyzer.api.ServiceOperationsAcceptanceTest.SUPPORT;
import static com.dealanalyzer.api.ServiceOperationsAcceptanceTest.opGet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dealanalyzer.ai.ModelHarness;
import com.dealanalyzer.ai.ModelProvider;
import com.dealanalyzer.ai.ModelSettings;
import com.dealanalyzer.ai.StubModelServer;
import com.dealanalyzer.ai.TestTasks;
import com.dealanalyzer.ai.UntrustedInput;
import com.dealanalyzer.operations.ProviderMeter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Per-case model usage through the application's own meter and usage store: provenance, tokens and
 * cost are recorded and visible to support, while document content and vendor text are not.
 */
@ExtendWith(OutputCaptureExtension.class)
class ModelUsageAcceptanceTest {
  private static final String SECRET = "Buyer Jane Q Example, SSN 123-45-6789";
  @TempDir Path directory;

  @Test void modelCallsAreMeteredPerCaseWithoutContent(CapturedOutput output) throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).start("model-usage", false, SETTINGS);
         var stub = new StubModelServer()) {
      assertThat(app.context().getBean(ModelHarness.class).available())
          .as("the shipped configuration keeps model paths off").isFalse();
      var created = post(app.client(), "/api/v1/cases", CasePortabilityAcceptanceTest.buyingCase());
      String id = created.at("/case/id").asText();

      stub.reply(200, message("claude-opus-5", "end_turn", "{\"total_minor\":3321000,\"currency\":\"USD\"}", 1_000_000, 200_000));
      stub.reply(200, message("claude-opus-5", "refusal", null, 400_000, 0));
      var meter = app.context().getBean(ProviderMeter.class);
      var harness = new ModelHarness(meter, new ModelSettings(true, ModelProvider.ANTHROPIC, "claude-opus-5", "pilot-parameters.v1", "corpus.v1"),
          stub.gateway(Duration.ofSeconds(5)), "agreement-under-test", new ObjectMapper());
      var input = List.<UntrustedInput>of(new UntrustedInput.Text("page 1 recognized text", SECRET));

      var result = harness.run(id, TestTasks.total(), input, 2);
      assertThat(result.value().totalMinor()).isEqualTo(3_321_000L);
      assertThatThrownBy(() -> harness.run(id, TestTasks.total(), input, 2)).isInstanceOf(ProviderMeter.Failure.class);

      var usage = opGet(app.client(), "/api/v1/operator/cases/" + id + "/usage", SUPPORT);
      assertThat(usage).hasSize(2);
      var refused = usage.get(0);
      var succeeded = usage.get(1);
      assertThat(succeeded.path("status").asText()).isEqualTo("SUCCESS");
      assertThat(succeeded.path("operation").asText()).isEqualTo("EXTRACTION");
      assertThat(succeeded.path("provider").asText()).isEqualTo("ANTHROPIC");
      assertThat(succeeded.path("providerVersion").asText()).isEqualTo("anthropic-messages-api");
      assertThat(succeeded.path("modelVersion").asText()).isEqualTo("claude-opus-5");
      assertThat(succeeded.path("promptVersion").asText()).isEqualTo("total-prompt.v1");
      assertThat(succeeded.path("schemaVersion").asText()).isEqualTo("total-schema.v1");
      assertThat(succeeded.path("thresholdSetVersion").asText()).isEqualTo("pilot-parameters.v1");
      assertThat(succeeded.path("evaluationSetVersion").asText()).isEqualTo("corpus.v1");
      assertThat(succeeded.path("license").asText()).isEqualTo("agreement-under-test");
      assertThat(succeeded.path("unit").asText()).isEqualTo("PAGE");
      assertThat(succeeded.path("units").asLong()).isEqualTo(2);
      assertThat(succeeded.path("inputTokens").asLong()).isEqualTo(1_000_000);
      assertThat(succeeded.path("outputTokens").asLong()).isEqualTo(200_000);
      assertThat(succeeded.path("costUsd").decimalValue()).isEqualByComparingTo("10.00");
      assertThat(refused.path("status").asText()).isEqualTo("PROVIDER_FAILURE");
      assertThat(refused.path("costUsd").decimalValue()).as("a refusal is still billed").isEqualByComparingTo("2.00");

      stub.reply(200, StubModelServer.completed(ModelProvider.OPENAI, "gpt-6-astra",
          "{\"total_minor\":3321000,\"currency\":\"USD\"}", 100_000, 10_000));
      var openAi = new ModelHarness(meter, new ModelSettings(true, ModelProvider.OPENAI, "gpt-6-astra", "pilot-parameters.v1", "corpus.v1"),
          stub.openAiGateway(Duration.ofSeconds(5)), "openai-agreement-under-test", new ObjectMapper());
      assertThat(openAi.run(id, TestTasks.total(), input, 1).provenance().provider()).isEqualTo(ModelProvider.OPENAI);
      var openAiRow = opGet(app.client(), "/api/v1/operator/cases/" + id + "/usage", SUPPORT).get(0);
      assertThat(openAiRow.path("provider").asText()).isEqualTo("OPENAI");
      assertThat(openAiRow.path("providerVersion").asText()).isEqualTo("openai-responses-api");
      assertThat(openAiRow.path("modelVersion").asText()).isEqualTo("gpt-6-astra");
      assertThat(openAiRow.path("license").asText()).isEqualTo("openai-agreement-under-test");
      assertThat(openAiRow.path("status").asText()).isEqualTo("SUCCESS");
      // 0.1M input at $10/M plus 0.01M output at $50/M.
      assertThat(openAiRow.path("costUsd").decimalValue()).isEqualByComparingTo("1.50");

      var providers = opGet(app.client(), "/api/v1/operator/providers", SUPPORT);
      assertThat(providers.findValuesAsText("provider")).contains("ANTHROPIC", "OPENAI");
      usage = opGet(app.client(), "/api/v1/operator/cases/" + id + "/usage", SUPPORT);
      assertThat(usage.toString() + providers).doesNotContain("Jane", "123-45-6789", "3321000");
    }
    assertThat(output.getAll()).doesNotContain("Jane", "123-45-6789", "test-key-not-a-credential");
  }
}

package com.dealanalyzer.ai;

import static com.dealanalyzer.ai.StubModelServer.completed;
import static com.dealanalyzer.ai.StubModelServer.refusal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dealanalyzer.operations.ProviderMeter;
import com.dealanalyzer.operations.ProviderMeter.Status;
import com.dealanalyzer.operations.RecordingUsageStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** One provider per deployment, chosen by configuration, with no fallback to the other. */
class ModelProviderSelectionTest {
  private static final List<UntrustedInput> INPUT = List.of(new UntrustedInput.Text("page 1 recognized text", "Total: $1"));

  @Test void anthropicIsTheDefaultAndEachProviderHasItsOwnDefaultModel() {
    var defaults = ModelSettings.from(new MockEnvironment());
    assertThat(defaults.enabled()).isFalse();
    assertThat(defaults.provider()).isEqualTo(ModelProvider.ANTHROPIC);
    assertThat(defaults.modelId()).isEqualTo("claude-opus-5-5");

    var openAi = ModelSettings.from(new MockEnvironment().withProperty("ai.model.provider", "openai"));
    assertThat(openAi.provider()).isEqualTo(ModelProvider.OPENAI);
    assertThat(openAi.modelId()).isEqualTo("gpt-6-astra");

    var blankModel = ModelSettings.from(new MockEnvironment()
        .withProperty("ai.model.provider", "OpenAI").withProperty("ai.model.id", " "));
    assertThat(blankModel.modelId()).as("the shipped empty ai.model.id selects the default").isEqualTo("gpt-6-astra");

    var explicit = ModelSettings.from(new MockEnvironment()
        .withProperty("ai.model.provider", "openai").withProperty("ai.model.id", "gpt-6.1-sol"));
    assertThat(explicit.modelId()).isEqualTo("gpt-6.1-sol");
  }

  @Test void anthropicPricesTheNewDefaultAndAnExplicitlyPinnedPreviousModel() {
    var defaults = ModelSettings.from(new MockEnvironment());
    assertThat(ModelPricing.costUsd(defaults.provider(), defaults.modelId(), 1_000_000, 0))
        .hasValueSatisfying(cost -> assertThat(cost).isEqualByComparingTo("4.00"));
    assertThat(ModelPricing.costUsd(defaults.provider(), defaults.modelId(), 0, 1_000_000))
        .hasValueSatisfying(cost -> assertThat(cost).isEqualByComparingTo("20.00"));

    var pinned = ModelSettings.from(new MockEnvironment().withProperty("ai.model.id", "claude-opus-5"));
    assertThat(pinned.modelId()).isEqualTo("claude-opus-5");
    assertThat(ModelPricing.costUsd(pinned.provider(), pinned.modelId(), 1_000_000, 0))
        .hasValueSatisfying(cost -> assertThat(cost).isEqualByComparingTo("5.00"));
    assertThat(ModelPricing.costUsd(pinned.provider(), pinned.modelId(), 0, 1_000_000))
        .hasValueSatisfying(cost -> assertThat(cost).isEqualByComparingTo("25.00"));
  }

  @Test void anUnknownProviderFailsStartup() {
    assertThatThrownBy(() -> ModelSettings.from(new MockEnvironment().withProperty("ai.model.provider", "anthropic,openai")))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("anthropic, openai");
    assertThatThrownBy(() -> ModelSettings.from(new MockEnvironment().withProperty("ai.model.provider", "")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test void theConfiguredProviderSelectsItsOwnGateway() {
    String key = "openai.apiKey";
    String anthropicKey = "anthropic.apiKey";
    System.setProperty(key, "test-key-not-a-credential");
    System.setProperty(anthropicKey, "test-key-not-a-credential");
    try {
      assertThat(ModelHarness.gateway(ModelProvider.OPENAI, Duration.ofSeconds(1))).isInstanceOf(OpenAiModelGateway.class);
      assertThat(ModelHarness.gateway(ModelProvider.ANTHROPIC, Duration.ofSeconds(1))).isInstanceOf(AnthropicModelGateway.class);
    } finally {
      System.clearProperty(key);
      System.clearProperty(anthropicKey);
    }
  }

  @Test void openAiCallsAreMeteredAsOpenAiWithTheirOwnProvenanceAndPrice() throws Exception {
    var store = new RecordingUsageStore();
    var meter = new ProviderMeter(store, Duration.ofSeconds(10), 1);
    try (var stub = new StubModelServer()) {
      stub.reply(200, completed(ModelProvider.OPENAI, "gpt-6-astra", "{\"total_minor\":100,\"currency\":\"USD\"}", 1_000_000, 100_000));
      var settings = new ModelSettings(true, ModelProvider.OPENAI, "gpt-6-astra", "pilot-parameters.v1", "corpus.v1");
      var harness = new ModelHarness(meter, settings, stub.openAiGateway(Duration.ofSeconds(10)), "openai-agreement", new ObjectMapper());

      var result = harness.run("case-1", TestTasks.total(), INPUT, 1);
      assertThat(result.value().totalMinor()).isEqualTo(100L);
      assertThat(result.provenance().provider()).isEqualTo(ModelProvider.OPENAI);
      assertThat(result.provenance().modelId()).isEqualTo("gpt-6-astra");
      assertThat(store.last().provenance()).isEqualTo(result.provenance());
      // 1M input at $10/M plus 0.1M output at $50/M.
      assertThat(store.last().usage().costUsd()).isEqualByComparingTo(new BigDecimal("15.00"));
    } finally { meter.close(); }
  }

  @Test void aRefusalFromTheConfiguredProviderIsNotRetriedElsewhere() throws Exception {
    for (ModelProvider provider : ModelProvider.values()) {
      var store = new RecordingUsageStore();
      var meter = new ProviderMeter(store, Duration.ofSeconds(10), 1);
      try (var stub = new StubModelServer()) {
        stub.reply(200, refusal(provider, provider.defaultModel(), 50, 1));
        stub.reply(200, completed(provider, provider.defaultModel(), "{\"total_minor\":1,\"currency\":\"USD\"}", 1, 1));
        var settings = new ModelSettings(true, provider, provider.defaultModel(), "pilot-parameters.v1", "corpus.v1");
        var harness = new ModelHarness(meter, settings, stub.gateway(provider, Duration.ofSeconds(10)), "agreement", new ObjectMapper());
        assertThatThrownBy(() -> harness.run("case-1", TestTasks.total(), INPUT, 1))
            .isInstanceOfSatisfying(ProviderMeter.Failure.class,
                failure -> assertThat(failure.status()).isEqualTo(Status.PROVIDER_FAILURE));
        assertThat(stub.requests()).as(provider + ": one call, no fallback or retry").hasSize(1);
        assertThat(store.rows).hasSize(1);
      } finally { meter.close(); }
    }
  }

  @Test void outputAttributedToAnotherProvidersModelIsRejected() throws Exception {
    var meter = new ProviderMeter(new RecordingUsageStore(), Duration.ofSeconds(10), 1);
    try (var stub = new StubModelServer()) {
      stub.reply(200, completed(ModelProvider.OPENAI, "gpt-6-luna", "{\"total_minor\":1,\"currency\":\"USD\"}", 1, 1));
      var settings = new ModelSettings(true, ModelProvider.OPENAI, "gpt-6-astra", "pilot-parameters.v1", "corpus.v1");
      var harness = new ModelHarness(meter, settings, stub.openAiGateway(Duration.ofSeconds(10)), "agreement", new ObjectMapper());
      assertThatThrownBy(() -> harness.run("case-1", TestTasks.total(), INPUT, 1))
          .isInstanceOfSatisfying(ProviderMeter.Failure.class,
              failure -> assertThat(failure.status()).isEqualTo(Status.CONTRACT_FAILURE));
    } finally { meter.close(); }
  }
}

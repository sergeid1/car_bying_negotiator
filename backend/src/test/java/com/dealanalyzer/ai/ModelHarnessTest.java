package com.dealanalyzer.ai;

import static com.dealanalyzer.ai.StubModelServer.error;
import static com.dealanalyzer.ai.StubModelServer.message;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dealanalyzer.ai.ModelGateway.ModelExchange;
import com.dealanalyzer.ai.ModelGateway.Outcome;
import com.dealanalyzer.operations.ProviderMeter;
import com.dealanalyzer.operations.ProviderMeter.Status;
import com.dealanalyzer.operations.RecordingUsageStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class ModelHarnessTest {
  private static final ModelSettings ENABLED = new ModelSettings(true, ModelProvider.ANTHROPIC, "claude-opus-5", "pilot-parameters.v1", "corpus.v1");
  private static final String SECRET = "BUYER-SSN-123-45-6789 VIN 1HGCV1F30LA000001";
  private static final List<UntrustedInput> INPUT = List.of(new UntrustedInput.Text("page 1 recognized text", SECRET));

  private final RecordingUsageStore store = new RecordingUsageStore();
  private final ProviderMeter meter = new ProviderMeter(store, Duration.ofSeconds(2), 2);

  @AfterEach void close() { meter.close(); }

  private ModelHarness harness(ModelGateway gateway) {
    return new ModelHarness(meter, ENABLED, gateway, "agreement-2026-01", new ObjectMapper());
  }

  private static ModelGateway replying(ModelExchange exchange) {
    return request -> exchange;
  }

  private static ModelExchange completed(String output) {
    return new ModelExchange(Outcome.COMPLETED, "claude-opus-5", output, 2_000_000, 100_000);
  }

  @Test void aValidOutputReturnsWithItsProvenanceAndIsMeteredWithCost() {
    var result = harness(replying(completed("{\"total_minor\":3150000,\"currency\":\"USD\"}")))
        .run("case-1", TestTasks.total(), INPUT, 3);

    assertThat(result.value()).isEqualTo(new TestTasks.Total(3_150_000L, "USD"));
    assertThat(result.provenance()).isEqualTo(new ModelProvenance(ModelProvider.ANTHROPIC,
        "claude-opus-5", "total-prompt.v1", "total-schema.v1", "pilot-parameters.v1", "corpus.v1"));
    var row = store.last();
    assertThat(row.status()).isEqualTo(Status.SUCCESS);
    assertThat(row.provenance()).isEqualTo(result.provenance());
    assertThat(row.license()).isEqualTo("agreement-2026-01");
    assertThat(row.units()).isEqualTo(3);
    assertThat(row.usage().inputTokens()).isEqualTo(2_000_000);
    assertThat(row.usage().outputTokens()).isEqualTo(100_000);
    // 2M input at $5/M plus 0.1M output at $25/M.
    assertThat(row.usage().costUsd()).isEqualByComparingTo(new BigDecimal("12.50"));
  }

  @Test void eachUnavailableOrRejectedOutcomeFailsClosedWithItsStatusAndSpend() {
    record Case(Outcome outcome, Status status) {}
    for (var c : List.of(new Case(Outcome.RATE_LIMITED, Status.PROVIDER_UNAVAILABLE),
        new Case(Outcome.TIMED_OUT, Status.PROVIDER_UNAVAILABLE),
        new Case(Outcome.UNAVAILABLE, Status.PROVIDER_UNAVAILABLE),
        new Case(Outcome.REFUSED, Status.PROVIDER_FAILURE),
        new Case(Outcome.REJECTED, Status.PROVIDER_FAILURE),
        new Case(Outcome.TRUNCATED, Status.CONTRACT_FAILURE),
        new Case(Outcome.MALFORMED, Status.CONTRACT_FAILURE))) {
      var exchange = new ModelExchange(c.outcome(), "claude-opus-5", null, 700, 30);
      assertThatThrownBy(() -> harness(replying(exchange)).run("case-1", TestTasks.total(), INPUT, 1))
          .isInstanceOfSatisfying(ProviderMeter.Failure.class, failure -> assertThat(failure.status()).isEqualTo(c.status()))
          .hasNoCause();
      assertThat(store.last().status()).isEqualTo(c.status());
      assertThat(store.last().usage().inputTokens()).as("billed tokens are metered on failure too").isEqualTo(700);
    }
  }

  @Test void outputOutsideTheSchemaOrFromAnotherModelIsRejectedNotRepaired() {
    for (String output : List.of(
        "{\"total_minor\":3150000}",
        "{\"total_minor\":3150000,\"currency\":\"USD\",\"note\":\"extra\"}",
        "{\"total_minor\":\"31,500\",\"currency\":\"USD\"}",
        "{\"total_minor\":3150000,\"currency\":\"EUR\"}",
        "[1,2]",
        "{\"total_minor\":315",
        "")) {
      assertThatThrownBy(() -> harness(replying(completed(output))).run("case-1", TestTasks.total(), INPUT, 1))
          .isInstanceOfSatisfying(ProviderMeter.Failure.class,
              failure -> assertThat(failure.status()).isEqualTo(Status.CONTRACT_FAILURE));
    }
    var otherModel = new ModelExchange(Outcome.COMPLETED, "claude-opus-4-8",
        "{\"total_minor\":3150000,\"currency\":\"USD\"}", 1, 1);
    assertThatThrownBy(() -> harness(replying(otherModel)).run("case-1", TestTasks.total(), INPUT, 1))
        .isInstanceOfSatisfying(ProviderMeter.Failure.class,
            failure -> assertThat(failure.status()).isEqualTo(Status.CONTRACT_FAILURE));
  }

  @Test void anUnreadableValueStaysMissingRatherThanGuessed() {
    var result = harness(replying(completed("{\"total_minor\":null,\"currency\":\"USD\"}")))
        .run("case-1", TestTasks.total(), INPUT, 1);
    assertThat(result.value().totalMinor()).isNull();
  }

  @Test void theMeterTimeoutBoundsASlowGateway() {
    var slow = new ProviderMeter(store, Duration.ofMillis(100), 1);
    try {
      var harness = new ModelHarness(slow, ENABLED, request -> {
        try { Thread.sleep(2_000); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
        return completed("{\"total_minor\":1,\"currency\":\"USD\"}");
      }, "agreement-2026-01", new ObjectMapper());
      assertThatThrownBy(() -> harness.run("case-1", TestTasks.total(), INPUT, 1))
          .isInstanceOfSatisfying(ProviderMeter.Failure.class,
              failure -> assertThat(failure.status()).isEqualTo(Status.PROVIDER_UNAVAILABLE));
      assertThat(store.last().status()).isEqualTo(Status.PROVIDER_UNAVAILABLE);
    } finally { slow.close(); }
  }

  @Test void disabledModelPathsAreUnavailableWithoutContactingAnyone() {
    var calls = new AtomicInteger();
    var disabled = new ModelHarness(meter, new ModelSettings(false, ModelProvider.ANTHROPIC, "claude-opus-5", "pilot-parameters.v1", "none"),
        request -> { calls.incrementAndGet(); return completed("{}"); }, "none", new ObjectMapper());
    assertThat(disabled.available()).isFalse();
    assertThatThrownBy(() -> disabled.run("case-1", TestTasks.total(), INPUT, 1))
        .isInstanceOfSatisfying(ProviderMeter.Failure.class,
            failure -> assertThat(failure.status()).isEqualTo(Status.PROVIDER_UNAVAILABLE));
    assertThat(calls).hasValue(0);
    assertThat(store.rows).isEmpty();
  }

  @Test void noDocumentContentOrVendorTextReachesLogsThroughTheRealClient(CapturedOutput output) throws Exception {
    try (var stub = new StubModelServer()) {
      stub.reply(200, message("claude-opus-5", "end_turn", "{\"total_minor\":1,\"currency\":\"USD\",\"leak\":\"" + SECRET + "\"}", 5, 5));
      stub.reply(400, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"" + SECRET + "\"}}");
      stub.reply(200, message("claude-opus-5", "end_turn", "{\"total_minor\":1,\"currency\":\"USD\"}", 5, 5));
      var harness = harness(stub.gateway(Duration.ofSeconds(2)));
      assertThatThrownBy(() -> harness.run("case-1", TestTasks.total(), INPUT, 1))
          .isInstanceOf(ProviderMeter.Failure.class).hasMessageNotContaining(SECRET);
      assertThatThrownBy(() -> harness.run("case-1", TestTasks.total(), INPUT, 1))
          .isInstanceOf(ProviderMeter.Failure.class).hasMessageNotContaining(SECRET);
      harness.run("case-1", TestTasks.total(), INPUT, 1);
    }
    assertThat(output.getAll()).doesNotContain(SECRET).doesNotContain("BUYER-SSN").doesNotContain("test-key-not-a-credential");
    assertThat(store.rows).extracting(RecordingUsageStore.Row::status)
        .containsExactly(Status.CONTRACT_FAILURE, Status.PROVIDER_FAILURE, Status.SUCCESS);
  }

  @Test void refusalThroughTheRealClientFailsClosed() throws Exception {
    try (var stub = new StubModelServer()) {
      stub.reply(200, message("claude-opus-5", "refusal", null, 40, 2));
      stub.reply(429, error("rate_limit_error"));
      var harness = harness(stub.gateway(Duration.ofSeconds(2)));
      assertThatThrownBy(() -> harness.run("case-1", TestTasks.total(), INPUT, 1))
          .isInstanceOfSatisfying(ProviderMeter.Failure.class,
              failure -> assertThat(failure.status()).isEqualTo(Status.PROVIDER_FAILURE));
      assertThatThrownBy(() -> harness.run("case-1", TestTasks.total(), INPUT, 1))
          .isInstanceOfSatisfying(ProviderMeter.Failure.class,
              failure -> assertThat(failure.status()).isEqualTo(Status.PROVIDER_UNAVAILABLE));
    }
  }
}

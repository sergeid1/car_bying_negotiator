package com.dealanalyzer.ai;

import static com.dealanalyzer.ai.StubAnthropicServer.error;
import static com.dealanalyzer.ai.StubAnthropicServer.message;
import static org.assertj.core.api.Assertions.assertThat;

import com.dealanalyzer.ai.ModelGateway.ModelRequest;
import com.dealanalyzer.ai.ModelGateway.Outcome;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnthropicModelGatewayTest {
  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private static ModelRequest request(UntrustedInput... inputs) {
    var task = TestTasks.total();
    return new ModelRequest("claude-opus-5", task.instructions(), task.outputSchema(), task.maxOutputTokens(), List.of(inputs));
  }

  @Test void sendsTheConfiguredModelFixedInstructionsAndStructuredOutputSchema() throws Exception {
    try (var stub = new StubAnthropicServer()) {
      stub.reply(200, message("claude-opus-5", "end_turn", "{\"total_minor\":3150000,\"currency\":\"USD\"}", 1200, 40));
      var exchange = stub.gateway(TIMEOUT).send(request(
          new UntrustedInput.Text("page 1 recognized text", "Total: $31,500.00"),
          new UntrustedInput.PngImage("page 1 image", new byte[] {1, 2, 3})));

      assertThat(exchange.outcome()).isEqualTo(Outcome.COMPLETED);
      assertThat(exchange.servedModel()).isEqualTo("claude-opus-5");
      assertThat(exchange.output()).isEqualTo("{\"total_minor\":3150000,\"currency\":\"USD\"}");
      assertThat(exchange.inputTokens()).isEqualTo(1200);
      assertThat(exchange.outputTokens()).isEqualTo(40);

      var sent = stub.requests().getFirst();
      assertThat(sent.path("model").asText()).isEqualTo("claude-opus-5");
      assertThat(sent.path("system").asText()).startsWith(TestTasks.INSTRUCTIONS).contains("untrusted data");
      assertThat(sent.at("/output_config/format/type").asText()).isEqualTo("json_schema");
      assertThat(sent.at("/output_config/format/schema/additionalProperties").asBoolean(true)).isFalse();
      assertThat(sent.has("fallbacks")).as("no silent fallback to an unevaluated model").isFalse();
      var content = sent.at("/messages/0/content");
      assertThat(sent.at("/messages/0/role").asText()).isEqualTo("user");
      assertThat(content.get(0).path("text").asText())
          .isEqualTo("<untrusted_data label=\"page 1 recognized text\">Total: $31,500.00</untrusted_data>");
      assertThat(content.get(2).path("type").asText()).isEqualTo("image");
      assertThat(content.get(2).at("/source/media_type").asText()).isEqualTo("image/png");
    }
  }

  @Test void refusalAndTruncationAreReportedWithTheirTokenSpend() throws Exception {
    try (var stub = new StubAnthropicServer()) {
      stub.reply(200, message("claude-opus-5", "refusal", null, 900, 5));
      stub.reply(200, message("claude-opus-5", "max_tokens", "{\"total_minor\":31", 900, 1024));
      stub.reply(200, message("claude-opus-5", "tool_use", null, 900, 10));
      var gateway = stub.gateway(TIMEOUT);
      var refused = gateway.send(request(new UntrustedInput.Text("page 1 recognized text", "x")));
      var truncated = gateway.send(request(new UntrustedInput.Text("page 1 recognized text", "x")));
      var unexpected = gateway.send(request(new UntrustedInput.Text("page 1 recognized text", "x")));

      assertThat(refused.outcome()).isEqualTo(Outcome.REFUSED);
      assertThat(refused.output()).isNull();
      assertThat(refused.inputTokens()).isEqualTo(900);
      assertThat(truncated.outcome()).isEqualTo(Outcome.TRUNCATED);
      assertThat(truncated.output()).as("a partial value is never handed on").isNull();
      assertThat(truncated.outputTokens()).isEqualTo(1024);
      assertThat(unexpected.outcome()).isEqualTo(Outcome.MALFORMED);
    }
  }

  @Test void vendorErrorsBecomeOutcomesWithoutRetrying() throws Exception {
    try (var stub = new StubAnthropicServer()) {
      stub.reply(429, error("rate_limit_error"));
      stub.reply(529, error("overloaded_error"));
      stub.reply(500, error("api_error"));
      stub.reply(401, error("authentication_error"));
      stub.reply(400, error("invalid_request_error"));
      stub.reply(200, "this is not json");
      var gateway = stub.gateway(TIMEOUT);
      var outcomes = java.util.stream.IntStream.range(0, 6)
          .mapToObj(i -> gateway.send(request(new UntrustedInput.Text("page 1 recognized text", "x"))).outcome())
          .toList();

      assertThat(outcomes).containsExactly(Outcome.RATE_LIMITED, Outcome.UNAVAILABLE, Outcome.UNAVAILABLE,
          Outcome.REJECTED, Outcome.REJECTED, Outcome.MALFORMED);
      assertThat(stub.requests()).as("the SDK must not retry behind the meter's back").hasSize(6);
    }
  }

  @Test void aSlowVendorTimesOut() throws Exception {
    try (var stub = new StubAnthropicServer()) {
      stub.reply(200, message("claude-opus-5", "end_turn", "{}", 1, 1), 3_000);
      var exchange = stub.gateway(Duration.ofMillis(300))
          .send(request(new UntrustedInput.Text("page 1 recognized text", "x")));
      assertThat(exchange.outcome()).isEqualTo(Outcome.TIMED_OUT);
    }
  }
}

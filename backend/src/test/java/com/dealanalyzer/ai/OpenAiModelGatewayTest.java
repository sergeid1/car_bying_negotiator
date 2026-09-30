package com.dealanalyzer.ai;

import static com.dealanalyzer.ai.StubModelServer.outputText;
import static com.dealanalyzer.ai.StubModelServer.refusalContent;
import static com.dealanalyzer.ai.StubModelServer.response;
import static com.dealanalyzer.ai.StubModelServer.responseWithOutput;
import static org.assertj.core.api.Assertions.assertThat;

import com.dealanalyzer.ai.ModelGateway.ModelRequest;
import com.dealanalyzer.ai.ModelGateway.Outcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenAiModelGatewayTest {
  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final String MODEL = "gpt-6-astra";
  private static final String VALID = "{\"total_minor\":3150000,\"currency\":\"USD\"}";

  private static ModelRequest request(UntrustedInput... inputs) {
    var task = TestTasks.total();
    return new ModelRequest(MODEL, task.instructions(), task.outputSchema(), task.maxOutputTokens(), List.of(inputs));
  }

  private static ModelRequest simple() {
    return request(new UntrustedInput.Text("page 1 recognized text", "x"));
  }

  private static String error(String type) {
    return "{\"error\":{\"message\":\"stub\",\"type\":\"" + type + "\",\"param\":null,\"code\":null}}";
  }

  @Test void sendsTheConfiguredModelFixedInstructionsStrictSchemaAndNoStorage() throws Exception {
    try (var stub = new StubModelServer()) {
      stub.reply(200, response(MODEL, "completed", null, List.of(outputText(VALID)), 1200, 40));
      var exchange = stub.openAiGateway(TIMEOUT).send(request(
          new UntrustedInput.Text("page 1 recognized text", "Total: $31,500.00 <b>"),
          new UntrustedInput.PngImage("page 1 image", new byte[] {1, 2, 3})));

      assertThat(exchange.outcome()).isEqualTo(Outcome.COMPLETED);
      assertThat(exchange.servedModel()).isEqualTo(MODEL);
      assertThat(exchange.output()).isEqualTo(VALID);
      assertThat(exchange.inputTokens()).isEqualTo(1200);
      assertThat(exchange.outputTokens()).isEqualTo(40);

      assertThat(stub.paths()).containsExactly("/v1/responses");
      var sent = stub.requests().getFirst();
      assertThat(sent.path("model").asText()).isEqualTo(MODEL);
      assertThat(sent.path("instructions").asText())
          .isEqualTo(UntrustedFraming.instructions(TestTasks.INSTRUCTIONS));
      assertThat(sent.path("store").asBoolean(true)).as("the vendor must not retain the response").isFalse();
      assertThat(sent.path("max_output_tokens").asInt()).isEqualTo(1024);
      assertThat(sent.has("tools")).isFalse();
      var format = sent.at("/text/format");
      assertThat(format.path("type").asText()).isEqualTo("json_schema");
      assertThat(format.path("strict").asBoolean()).isTrue();
      assertThat(format.path("schema")).isEqualTo(new ObjectMapper().valueToTree(TestTasks.total().outputSchema()));

      assertThat(sent.path("input")).hasSize(1);
      assertThat(sent.at("/input/0/role").asText()).isEqualTo("user");
      var content = sent.at("/input/0/content");
      assertThat(content.get(0).path("type").asText()).isEqualTo("input_text");
      assertThat(content.get(0).path("text").asText()).as("framed exactly as for Anthropic")
          .isEqualTo("<untrusted_data label=\"page 1 recognized text\">Total: $31,500.00 &lt;b&gt;</untrusted_data>");
      assertThat(content.get(1).path("text").asText()).startsWith("<untrusted_data label=\"page 1 image\">");
      assertThat(content.get(2).path("type").asText()).isEqualTo("input_image");
      assertThat(content.get(2).path("image_url").asText()).isEqualTo("data:image/png;base64,AQID");
    }
  }

  @Test void refusalsIncompleteAndUnexpectedOutputAreReportedWithTheirSpend() throws Exception {
    try (var stub = new StubModelServer()) {
      stub.reply(200, response(MODEL, "completed", null, List.of(refusalContent()), 900, 5));
      stub.reply(200, response(MODEL, "incomplete", "max_output_tokens", List.of(outputText("{\"total_minor\":31")), 900, 1024));
      stub.reply(200, response(MODEL, "incomplete", "content_filter", List.of(), 900, 3));
      stub.reply(200, response(MODEL, "failed", null, List.of(), 900, 0));
      var toolCall = new ObjectMapper().createObjectNode().put("type", "function_call").put("id", "fc_1")
          .put("call_id", "call_1").put("name", "approve").put("arguments", "{}").put("status", "completed");
      stub.reply(200, responseWithOutput(MODEL, "completed", null, List.of(toolCall), 900, 10));
      stub.reply(200, response(MODEL, "in_progress", null, List.of(), 900, 0));
      var gateway = stub.openAiGateway(TIMEOUT);
      var outcomes = java.util.stream.IntStream.range(0, 6).mapToObj(i -> gateway.send(simple())).toList();

      assertThat(outcomes).extracting(ModelGateway.ModelExchange::outcome).containsExactly(
          Outcome.REFUSED, Outcome.TRUNCATED, Outcome.REFUSED, Outcome.UNAVAILABLE, Outcome.MALFORMED, Outcome.MALFORMED);
      assertThat(outcomes).allSatisfy(exchange -> assertThat(exchange.output()).as("never a partial value").isNull());
      assertThat(outcomes.get(0).inputTokens()).isEqualTo(900);
      assertThat(outcomes.get(1).outputTokens()).isEqualTo(1024);
    }
  }

  @Test void vendorErrorsBecomeOutcomesWithoutRetrying() throws Exception {
    try (var stub = new StubModelServer()) {
      stub.reply(429, error("rate_limit_exceeded"));
      stub.reply(503, error("server_error"));
      stub.reply(500, error("server_error"));
      stub.reply(401, error("invalid_request_error"));
      stub.reply(400, error("invalid_request_error"));
      stub.reply(200, "this is not json");
      var gateway = stub.openAiGateway(TIMEOUT);
      var outcomes = java.util.stream.IntStream.range(0, 6).mapToObj(i -> gateway.send(simple()).outcome()).toList();

      assertThat(outcomes).containsExactly(Outcome.RATE_LIMITED, Outcome.UNAVAILABLE, Outcome.UNAVAILABLE,
          Outcome.REJECTED, Outcome.REJECTED, Outcome.MALFORMED);
      assertThat(stub.requests()).as("the SDK must not retry behind the meter's back").hasSize(6);
    }
  }

  @Test void aSlowVendorTimesOut() throws Exception {
    try (var stub = new StubModelServer()) {
      stub.reply(200, response(MODEL, "completed", null, List.of(outputText(VALID)), 1, 1), 3_000);
      assertThat(stub.openAiGateway(Duration.ofMillis(300)).send(simple()).outcome()).isEqualTo(Outcome.TIMED_OUT);
    }
  }
}

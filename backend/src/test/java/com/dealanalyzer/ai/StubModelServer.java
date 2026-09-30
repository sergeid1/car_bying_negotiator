package com.dealanalyzer.ai;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * A loopback stand-in for the Anthropic Messages API and the OpenAI Responses API, so the real SDK
 * clients are exercised without any network call to a vendor. Replies are served in order; every
 * request path and body is recorded.
 */
public final class StubModelServer implements AutoCloseable {
  public record Reply(int status, String body, long delayMillis) {}

  private static final ObjectMapper JSON = new ObjectMapper();
  private final HttpServer server;
  private final ConcurrentLinkedQueue<Reply> replies = new ConcurrentLinkedQueue<>();
  private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
  private final List<String> paths = new CopyOnWriteArrayList<>();

  public StubModelServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext("/", exchange -> {
      try (exchange) {
        paths.add(exchange.getRequestURI().getPath());
        requests.add(JSON.readTree(exchange.getRequestBody()));
        Reply reply = replies.poll();
        if (reply == null) reply = new Reply(500, error("api_error"), 0);
        if (reply.delayMillis() > 0) {
          try { Thread.sleep(reply.delayMillis()); } catch (InterruptedException stopped) { return; }
        }
        byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(reply.status(), body.length);
        exchange.getResponseBody().write(body);
      } catch (IOException closedByClient) {
        // A timed-out client hangs up; nothing to report.
      }
    });
    server.start();
  }

  public String baseUrl() {
    return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
  }

  /** A gateway configured exactly as production, with a dummy key and this server as the endpoint. */
  public AnthropicModelGateway gateway(Duration timeout) {
    return new AnthropicModelGateway(AnthropicModelGateway.configure(
        AnthropicOkHttpClient.builder().apiKey("test-key-not-a-credential").baseUrl(baseUrl()), timeout));
  }

  /** The OpenAI gateway configured exactly as production, against this server. */
  public OpenAiModelGateway openAiGateway(Duration timeout) {
    return new OpenAiModelGateway(OpenAiModelGateway.configure(
        OpenAIOkHttpClient.builder().apiKey("test-key-not-a-credential").baseUrl(baseUrl() + "/v1"), timeout));
  }

  /** The gateway for a provider, so one test body can run against either SDK. */
  public ModelGateway gateway(ModelProvider provider, Duration timeout) {
    return provider == ModelProvider.OPENAI ? openAiGateway(timeout) : gateway(timeout);
  }

  /** A successful reply in the given provider's wire format. */
  public static String completed(ModelProvider provider, String model, String text, long input, long output) {
    return provider == ModelProvider.OPENAI
        ? response(model, "completed", null, List.of(outputText(text)), input, output)
        : message(model, "end_turn", text, input, output);
  }

  /** A refusal in the given provider's wire format. */
  public static String refusal(ModelProvider provider, String model, long input, long output) {
    return provider == ModelProvider.OPENAI
        ? response(model, "completed", null, List.of(refusalContent()), input, output)
        : message(model, "refusal", null, input, output);
  }

  public StubModelServer reply(int status, String body) { return reply(status, body, 0); }

  public StubModelServer reply(int status, String body, long delayMillis) {
    replies.add(new Reply(status, body, delayMillis));
    return this;
  }

  public List<JsonNode> requests() { return List.copyOf(requests); }

  public List<String> paths() { return List.copyOf(paths); }

  public static String message(String model, String stopReason, String text, long input, long output) {
    var root = JSON.createObjectNode();
    root.put("id", "msg_stub").put("type", "message").put("role", "assistant").put("model", model);
    var content = root.putArray("content");
    if (text != null) content.addObject().put("type", "text").put("text", text);
    root.put("stop_reason", stopReason).putNull("stop_sequence");
    root.putObject("usage").put("input_tokens", input).put("output_tokens", output);
    return root.toString();
  }

  /**
   * A Responses API body. {@code content} items go into one assistant message; pass
   * {@code outputItems} null to use that message, or a raw output array to replace it.
   */
  public static String response(String model, String status, String incompleteReason,
      List<com.fasterxml.jackson.databind.node.ObjectNode> content, long input, long output) {
    var message = JSON.createObjectNode();
    message.put("type", "message").put("id", "msg_stub").put("status", "completed").put("role", "assistant");
    content.forEach(message.putArray("content")::add);
    return responseWithOutput(model, status, incompleteReason, List.of(message), input, output);
  }

  public static String responseWithOutput(String model, String status, String incompleteReason,
      List<com.fasterxml.jackson.databind.node.ObjectNode> items, long input, long output) {
    var root = JSON.createObjectNode();
    root.put("id", "resp_stub").put("object", "response").put("created_at", 1_760_000_000L)
        .put("status", status).put("model", model).putNull("error").putNull("instructions")
        .put("parallel_tool_calls", false).put("tool_choice", "auto").putNull("temperature").putNull("top_p");
    root.putArray("tools");
    root.putObject("metadata");
    if (incompleteReason == null) root.putNull("incomplete_details");
    else root.putObject("incomplete_details").put("reason", incompleteReason);
    var reasoning = JSON.createObjectNode().put("type", "reasoning").put("id", "rs_stub");
    reasoning.putArray("summary");
    var array = root.putArray("output");
    array.add(reasoning);
    items.forEach(array::add);
    var usage = root.putObject("usage");
    usage.put("input_tokens", input).put("output_tokens", output).put("total_tokens", input + output);
    usage.putObject("input_tokens_details").put("cached_tokens", 0);
    usage.putObject("output_tokens_details").put("reasoning_tokens", 0);
    return root.toString();
  }

  public static com.fasterxml.jackson.databind.node.ObjectNode outputText(String text) {
    var node = JSON.createObjectNode().put("type", "output_text").put("text", text);
    node.putArray("annotations");
    return node;
  }

  public static com.fasterxml.jackson.databind.node.ObjectNode refusalContent() {
    return JSON.createObjectNode().put("type", "refusal").put("refusal", "stub refusal");
  }

  public static String error(String type) {
    return "{\"type\":\"error\",\"error\":{\"type\":\"" + type + "\",\"message\":\"stub\"}}";
  }

  @Override
  public void close() {
    server.stop(0);
  }
}

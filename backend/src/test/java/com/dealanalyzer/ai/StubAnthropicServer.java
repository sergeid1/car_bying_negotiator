package com.dealanalyzer.ai;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
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
 * A loopback stand-in for the Messages API, so the real SDK client is exercised without any network
 * call to the vendor. Replies are served in order; every request body is recorded.
 */
public final class StubAnthropicServer implements AutoCloseable {
  public record Reply(int status, String body, long delayMillis) {}

  private static final ObjectMapper JSON = new ObjectMapper();
  private final HttpServer server;
  private final ConcurrentLinkedQueue<Reply> replies = new ConcurrentLinkedQueue<>();
  private final List<JsonNode> requests = new CopyOnWriteArrayList<>();

  public StubAnthropicServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext("/v1/messages", exchange -> {
      try (exchange) {
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

  public StubAnthropicServer reply(int status, String body) { return reply(status, body, 0); }

  public StubAnthropicServer reply(int status, String body, long delayMillis) {
    replies.add(new Reply(status, body, delayMillis));
    return this;
  }

  public List<JsonNode> requests() { return List.copyOf(requests); }

  public static String message(String model, String stopReason, String text, long input, long output) {
    var root = JSON.createObjectNode();
    root.put("id", "msg_stub").put("type", "message").put("role", "assistant").put("model", model);
    var content = root.putArray("content");
    if (text != null) content.addObject().put("type", "text").put("text", text);
    root.put("stop_reason", stopReason).putNull("stop_sequence");
    root.putObject("usage").put("input_tokens", input).put("output_tokens", output);
    return root.toString();
  }

  public static String error(String type) {
    return "{\"type\":\"error\",\"error\":{\"type\":\"" + type + "\",\"message\":\"stub\"}}";
  }

  @Override
  public void close() {
    server.stop(0);
  }
}

package com.dealanalyzer.vehicle.safety;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "vehicle-safety.provider", havingValue = "nhtsa")
public class NhtsaVehicleSafetyProvider implements VehicleSafetyProvider {
  static final int MAX_BODY_BYTES = 1024 * 1024;
  private final URI base;
  private final Duration timeout;
  private final Duration delay;
  private final int attempts;
  private final ObjectMapper json;
  private final HttpClient client;

  public NhtsaVehicleSafetyProvider(
      @Value("${vehicle-safety.nhtsa.base-url:https://api.nhtsa.gov}") String baseUrl,
      @Value("${vehicle-safety.nhtsa.timeout:PT4S}") Duration timeout,
      @Value("${vehicle-safety.nhtsa.attempts:2}") int attempts,
      @Value("${vehicle-safety.nhtsa.retry-delay:PT0.25S}") Duration delay, ObjectMapper json) {
    base = URI.create(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
    boolean local = "http".equals(base.getScheme()) && ("localhost".equals(base.getHost()) || "127.0.0.1".equals(base.getHost()));
    if ((!"https".equals(base.getScheme()) && !local) || base.getHost() == null
        || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null)
      throw new IllegalArgumentException("NHTSA base URL must use HTTPS");
    if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(30)) > 0
        || attempts < 1 || attempts > 3 || delay.isNegative() || delay.compareTo(Duration.ofSeconds(5)) > 0)
      throw new IllegalArgumentException("Invalid NHTSA timeout or retry bounds");
    this.timeout = timeout; this.attempts = attempts; this.delay = delay; this.json = json;
    client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
  }

  @Override public Facts retrieve(Vehicle vehicle) {
    var recalls = new ArrayList<Recall>();
    for (var row : get("/recalls/recallsByVehicle?make=" + encode(vehicle.make()) + "&model="
        + encode(vehicle.model()) + "&modelYear=" + vehicle.year())) {
      recalls.add(new Recall(required(row, "NHTSACampaignNumber"), required(row, "Component"),
          required(row, "Summary"), text(row, "Consequence"), text(row, "Remedy"), text(row, "ReportReceivedDate")));
    }
    var variants = get("/SafetyRatings/modelyear/" + vehicle.year() + "/make/" + encode(vehicle.make())
        + "/model/" + encode(vehicle.model()) + "?format=json");
    if (variants.size() > 50) throw unavailable();
    var ratings = new ArrayList<Rating>();
    for (var variant : variants) {
      String id = required(variant, "VehicleId");
      if (!id.matches("[0-9]{1,12}")) throw unavailable();
      var details = get("/SafetyRatings/VehicleId/" + id + "?format=json");
      if (details.size() != 1) throw unavailable();
      var row = details.get(0);
      ratings.add(new Rating(id, required(variant, "VehicleDescription"), stars(row, "OverallRating"),
          stars(row, "OverallFrontCrashRating"), stars(row, "OverallSideCrashRating"),
          stars(row, "RolloverRating"), risk(row)));
    }
    return new Facts("AVAILABLE", "NHTSA", Instant.now(), vehicle, recalls, ratings);
  }

  private JsonNode get(String path) {
    var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout)
        .header("Accept", "application/json").GET().build();
    for (int attempt = 1; attempt <= attempts; attempt++) {
      CompletableFuture<HttpResponse<byte[]>> pending = null;
      try {
        if (attempt > 1) Thread.sleep(delay.toMillis());
        pending = client.sendAsync(request, info -> new BoundedBody());
        var response = pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        if (response.statusCode() == 200) {
          var root = json.readTree(response.body());
          if (root == null) throw unavailable();
          var rows = root.has("results") ? root.path("results") : root.path("Results");
          if (!rows.isArray() || rows.size() > 1000) throw unavailable();
          return rows;
        }
        if (response.statusCode() != 429 && response.statusCode() < 500) throw unavailable();
      } catch (IOException failure) {
        throw unavailable();
      } catch (TimeoutException timeoutFailure) {
        if (pending != null) pending.cancel(true);
      } catch (ExecutionException transportFailure) {
        if (transportFailure.getCause() instanceof BodyTooLarge) throw unavailable();
      } catch (InterruptedException interrupted) {
        if (pending != null) pending.cancel(true);
        Thread.currentThread().interrupt(); throw unavailable();
      }
    }
    throw unavailable();
  }

  private static final class BodyTooLarge extends RuntimeException {}

  /** Enforces the byte cap while receiving; the request deadline also covers a stalled body. */
  private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private Flow.Subscription subscription;
    @Override public CompletionStage<byte[]> getBody() { return result; }
    @Override public void onSubscribe(Flow.Subscription subscription) {
      this.subscription = subscription; subscription.request(1);
    }
    @Override public void onNext(List<ByteBuffer> buffers) {
      for (var buffer : buffers) {
        if (buffer.remaining() > MAX_BODY_BYTES - bytes.size()) {
          subscription.cancel(); result.completeExceptionally(new BodyTooLarge()); return;
        }
        byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
      }
      subscription.request(1);
    }
    @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
    @Override public void onComplete() { result.complete(bytes.toByteArray()); }
  }
  private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
  private static String text(JsonNode row, String key) {
    var node = row.path(key);
    if (!node.isTextual() && !node.isNumber()) return null;
    String value = node.asText().strip();
    if (value.length() > 20000) throw unavailable();
    return value.isEmpty() ? null : value;
  }
  private static String required(JsonNode row, String key) {
    String value = text(row, key); if (value == null) throw unavailable(); return value;
  }
  private static String stars(JsonNode row, String key) {
    String value = text(row, key); return value != null && value.matches("[1-5]") ? value : null;
  }
  private static String risk(JsonNode row) {
    String value = text(row, "RolloverPossibility");
    try { double risk = Double.parseDouble(value); return risk >= 0 && risk <= 1 ? value : null; }
    catch (RuntimeException invalid) { return null; }
  }
  private static IllegalStateException unavailable() { return new IllegalStateException("NHTSA did not answer"); }
}

package com.dealanalyzer.journey.vin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The free public NHTSA vPIC decoder ({@code DecodeVinValues}), selected by
 * {@code vin-decoder.provider=nhtsa-vpic}. Only the VIN is sent; no account, case or Buyer identity.
 * Each attempt has a timeout; a connection failure, timeout, 429 or 5xx is retried up to the
 * configured attempts, then reported as unavailable. Values are passed through as vPIC states
 * them, and an attribute vPIC leaves empty stays absent. Terms: docs/provider-inventory.md.
 */
@Component
@ConditionalOnProperty(name = "vin-decoder.provider", havingValue = "nhtsa-vpic")
public class NhtsaVpicVinDecoder implements VinDecoder {
  public static final String SOURCE = "NHTSA vPIC";
  public static final String VERSION = "nhtsa-vpic-decodevinvalues-adapter-v1";
  private static final int MAX_BODY_BYTES = 256 * 1024;

  private final URI baseUrl;
  private final Duration timeout;
  private final int attempts;
  private final Duration retryDelay;
  private final ObjectMapper json;
  private final HttpClient client;

  @Autowired
  public NhtsaVpicVinDecoder(@Value("${vin-decoder.nhtsa-vpic.base-url:https://vpic.nhtsa.dot.gov/api/vehicles}") String baseUrl,
      @Value("${vin-decoder.nhtsa-vpic.timeout:PT4S}") Duration timeout,
      @Value("${vin-decoder.nhtsa-vpic.attempts:2}") int attempts,
      @Value("${vin-decoder.nhtsa-vpic.retry-delay:PT0.25S}") Duration retryDelay, ObjectMapper json) {
    URI parsed = URI.create(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
    boolean local = "http".equals(parsed.getScheme())
        && ("127.0.0.1".equals(parsed.getHost()) || "localhost".equals(parsed.getHost()));
    if (!"https".equals(parsed.getScheme()) && !local)
      throw new IllegalArgumentException("vin-decoder.nhtsa-vpic.base-url must use https");
    if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(30)) > 0)
      throw new IllegalArgumentException("vin-decoder.nhtsa-vpic.timeout must be between 0 and 30 seconds");
    if (attempts < 1 || attempts > 3) throw new IllegalArgumentException("vin-decoder.nhtsa-vpic.attempts must be 1 to 3");
    if (retryDelay.isNegative() || retryDelay.compareTo(Duration.ofSeconds(5)) > 0)
      throw new IllegalArgumentException("vin-decoder.nhtsa-vpic.retry-delay must be at most 5 seconds");
    this.baseUrl = parsed; this.timeout = timeout; this.attempts = attempts; this.retryDelay = retryDelay; this.json = json;
    this.client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
  }

  @Override public VinDecoding decode(String vin) {
    var request = HttpRequest.newBuilder(URI.create(baseUrl + "/DecodeVinValues/" + vin + "?format=json"))
        .timeout(timeout).header("Accept", "application/json").GET().build();
    String reason = "vPIC did not answer";
    for (int attempt = 1; attempt <= attempts; attempt++) {
      if (attempt > 1) pause();
      try {
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        int status = response.statusCode();
        byte[] body;
        try (var stream = response.body()) { body = stream.readNBytes(MAX_BODY_BYTES + 1); }
        if (status == 200) {
          if (body.length > MAX_BODY_BYTES) throw new VinDecoderUnavailableException("vPIC answer too large");
          return parse(body);
        }
        reason = "vPIC answered HTTP " + status;
        if (status != 429 && status < 500) break;
      } catch (IOException failure) {
        reason = "vPIC connection failed or timed out";
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new VinDecoderUnavailableException("Interrupted while waiting for vPIC");
      }
    }
    throw new VinDecoderUnavailableException(reason);
  }

  private void pause() {
    try { Thread.sleep(retryDelay.toMillis()); }
    catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new VinDecoderUnavailableException("Interrupted while waiting for vPIC");
    }
  }

  VinDecoding parse(byte[] body) {
    JsonNode result;
    try { result = json.readTree(body).path("Results").path(0); }
    catch (IOException unreadable) { throw new VinDecoderUnavailableException("vPIC answer unreadable"); }
    if (!result.isObject()) throw new VinDecoderUnavailableException("vPIC answer had no result");
    var attributes = new EnumMap<VehicleAttribute, String>(VehicleAttribute.class);
    attributes.put(VehicleAttribute.MODEL_YEAR, text(result, "ModelYear"));
    attributes.put(VehicleAttribute.MAKE, text(result, "Make"));
    attributes.put(VehicleAttribute.MODEL, text(result, "Model"));
    attributes.put(VehicleAttribute.TRIM, text(result, "Trim"));
    attributes.put(VehicleAttribute.BODY, text(result, "BodyClass"));
    attributes.put(VehicleAttribute.DRIVETRAIN, text(result, "DriveType"));
    attributes.put(VehicleAttribute.ENGINE, engine(result));
    attributes.put(VehicleAttribute.TRANSMISSION, transmission(result));
    return VinDecoding.of(SOURCE, VERSION, attributes);
  }

  /** For example "2.5 L 4-cylinder Gasoline", from only the parts vPIC supplied. */
  private static String engine(JsonNode result) {
    String displacement = text(result, "DisplacementL");
    if (displacement != null) {
      try { displacement = new BigDecimal(displacement).setScale(1, RoundingMode.HALF_UP).toPlainString() + " L"; }
      catch (NumberFormatException notNumeric) { displacement = null; }
    }
    String cylinders = text(result, "EngineCylinders");
    if (cylinders != null) cylinders = cylinders.matches("\\d{1,2}") ? cylinders + "-cylinder" : null;
    return join(displacement, cylinders, text(result, "FuelTypePrimary"));
  }

  /** For example "8-speed Automatic", from only the parts vPIC supplied. */
  private static String transmission(JsonNode result) {
    String speeds = text(result, "TransmissionSpeeds");
    String style = text(result, "TransmissionStyle");
    return join(speeds != null && speeds.matches("\\d{1,2}") ? speeds + "-speed" : null, style);
  }

  private static String join(String... parts) {
    String joined = Stream.of(parts).filter(Objects::nonNull).collect(Collectors.joining(" "));
    return joined.isEmpty() ? null : joined;
  }

  // vPIC uses empty strings, "Not Applicable" and "0" for values it does not have.
  private static final List<String> EMPTY = List.of("", "not applicable", "null");

  private static String text(JsonNode result, String field) {
    JsonNode node = result.path(field);
    if (!node.isTextual()) return null;
    String value = node.asText().strip();
    if (EMPTY.contains(value.toLowerCase(Locale.ROOT)) || value.equals("0") || value.length() > 200) return null;
    return value;
  }
}

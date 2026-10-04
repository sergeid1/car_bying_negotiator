package com.dealanalyzer.journey.vin;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** The live vPIC adapter against a local stand-in server; no test calls the real endpoint. */
class NhtsaVpicVinDecoderTest {
  private static final String VIN = "2T3P1RFV5MW000001";
  private static final String DECODED = """
      {"Count":136,"Message":"Results returned successfully","SearchCriteria":"VIN:2T3P1RFV5MW000001","Results":[{
        "ModelYear":"2021","Make":"TOYOTA","Model":"RAV4","Trim":"XLE","BodyClass":"Sport Utility Vehicle (SUV)/Multi-Purpose Vehicle (MPV)",
        "DriveType":"AWD/All-Wheel Drive","DisplacementL":"2.48723","EngineCylinders":"4","FuelTypePrimary":"Gasoline",
        "TransmissionStyle":"Automatic","TransmissionSpeeds":"8","ErrorCode":"0"}]}""";
  private static final String EMPTY = """
      {"Count":136,"Results":[{"ModelYear":"","Make":"","Model":"","Trim":"Not Applicable","BodyClass":"","DriveType":"",
        "DisplacementL":"","EngineCylinders":"","FuelTypePrimary":"","TransmissionStyle":"","TransmissionSpeeds":"","ErrorCode":"8"}]}""";

  private HttpServer server;
  private final List<String> paths = new CopyOnWriteArrayList<>();

  @AfterEach void stop() { if (server != null) server.stop(0); }

  private String serve(int... statuses) throws Exception { return serve(DECODED, 0, statuses); }

  private String serve(String body, long delayMillis, int... statuses) throws Exception {
    var calls = new AtomicInteger();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/vehicles", exchange -> {
      paths.add(exchange.getRequestURI().toString());
      int status = statuses[Math.min(calls.getAndIncrement(), statuses.length - 1)];
      try { Thread.sleep(delayMillis); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
      byte[] bytes = (status == 200 ? body : "{}").getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/vehicles";
  }

  private static NhtsaVpicVinDecoder decoder(String url, Duration timeout, int attempts) {
    return new NhtsaVpicVinDecoder(url, timeout, attempts, Duration.ofMillis(10), new ObjectMapper());
  }

  @Test void decodesTheEightAttributesFromWhatVpicSuppliesAndSendsOnlyTheVin() throws Exception {
    var decoding = decoder(serve(200), Duration.ofSeconds(2), 2).decode(VIN);
    assertThat(paths).containsExactly("/api/vehicles/DecodeVinValues/" + VIN + "?format=json");
    assertThat(decoding.outcome()).isEqualTo(VinDecoding.Outcome.DECODED);
    assertThat(decoding.source()).isEqualTo("NHTSA vPIC");
    assertThat(decoding.decoderVersion()).isEqualTo(NhtsaVpicVinDecoder.VERSION);
    assertThat(decoding.attributes()).containsExactly(
        entry(VehicleAttribute.MODEL_YEAR, "2021"), entry(VehicleAttribute.MAKE, "TOYOTA"), entry(VehicleAttribute.MODEL, "RAV4"),
        entry(VehicleAttribute.TRIM, "XLE"), entry(VehicleAttribute.BODY, "Sport Utility Vehicle (SUV)/Multi-Purpose Vehicle (MPV)"),
        entry(VehicleAttribute.DRIVETRAIN, "AWD/All-Wheel Drive"), entry(VehicleAttribute.ENGINE, "2.5 L 4-cylinder Gasoline"),
        entry(VehicleAttribute.TRANSMISSION, "8-speed Automatic"));
  }

  @Test void aVinVpicCannotDecodeIsUndecodableWithNothingFilledIn() throws Exception {
    var decoding = decoder(serve(EMPTY, 0, 200), Duration.ofSeconds(2), 1).decode(VIN);
    assertThat(decoding.outcome()).isEqualTo(VinDecoding.Outcome.UNDECODABLE);
    assertThat(decoding.attributes()).isEmpty();
  }

  @Test void serverErrorsAndRateLimitsAreRetriedThenStatedAsUnavailable() throws Exception {
    assertThat(decoder(serve(503, 200), Duration.ofSeconds(2), 2).decode(VIN).outcome()).isEqualTo(VinDecoding.Outcome.DECODED);
    assertThat(paths).hasSize(2);
    stop(); paths.clear();
    String url = serve(429, 500, 200);
    assertThatThrownBy(() -> decoder(url, Duration.ofSeconds(2), 2).decode(VIN))
        .isInstanceOf(VinDecoderUnavailableException.class).hasMessage("vPIC answered HTTP 500").hasMessageNotContaining(VIN);
    assertThat(paths).hasSize(2);
  }

  @Test void aClientErrorIsNotRetried() throws Exception {
    String url = serve(404, 200);
    assertThatThrownBy(() -> decoder(url, Duration.ofSeconds(2), 3).decode(VIN)).isInstanceOf(VinDecoderUnavailableException.class);
    assertThat(paths).hasSize(1);
  }

  @Test void aSlowAnswerTimesOutAndIsRetriedThenUnavailable() throws Exception {
    String url = serve(DECODED, 1500, 200);
    long started = System.nanoTime();
    assertThatThrownBy(() -> decoder(url, Duration.ofMillis(200), 2).decode(VIN))
        .isInstanceOf(VinDecoderUnavailableException.class).hasMessage("vPIC connection failed or timed out");
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1400));
  }

  @Test void anUnreadableAnswerIsUnavailableNotGuessed() throws Exception {
    String url = serve("<html>maintenance</html>", 0, 200);
    assertThatThrownBy(() -> decoder(url, Duration.ofSeconds(2), 1).decode(VIN)).isInstanceOf(VinDecoderUnavailableException.class);
  }

  @Test void configurationIsBounded() {
    var json = new ObjectMapper();
    assertThatThrownBy(() -> new NhtsaVpicVinDecoder("http://vpic.example", Duration.ofSeconds(1), 1, Duration.ZERO, json))
        .hasMessageContaining("https");
    assertThatThrownBy(() -> new NhtsaVpicVinDecoder("https://vpic.example", Duration.ZERO, 1, Duration.ZERO, json))
        .hasMessageContaining("timeout");
    assertThatThrownBy(() -> new NhtsaVpicVinDecoder("https://vpic.example", Duration.ofSeconds(1), 0, Duration.ZERO, json))
        .hasMessageContaining("attempts");
  }

  @Test void theFakeDecoderIsRefusedUnderPilotOrProduction() {
    var fake = new FakeVinDecoder();
    for (String deployment : new String[] {"pilot", "production", "prod"}) {
      var environment = new MockEnvironment().withProperty("evidence.deployment", deployment);
      assertThatThrownBy(() -> new VinDecoderProductionGuard(environment, fake).afterSingletonsInstantiated())
          .hasMessageContaining("fake VIN decoder");
    }
    var pilot = new MockEnvironment();
    pilot.setActiveProfiles("pilot");
    assertThatThrownBy(() -> new VinDecoderProductionGuard(pilot, fake).afterSingletonsInstantiated());
    new VinDecoderProductionGuard(pilot, decoder("https://vpic.example", Duration.ofSeconds(1), 1)).afterSingletonsInstantiated();
    new VinDecoderProductionGuard(new MockEnvironment(), fake).afterSingletonsInstantiated();
  }
}

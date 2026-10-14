package com.dealanalyzer.vehicle.safety;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class NhtsaVehicleSafetyProviderTest {
  static final VehicleSafetyProvider.Vehicle CAR = new VehicleSafetyProvider.Vehicle(2021, "Test Make", "A/B & C");
  static final String RECALL = """
      {"results":[{"NHTSACampaignNumber":"21V001000","Component":"AIR BAGS","Summary":"A model recall.",
      "Consequence":"An injury risk.","Remedy":"Replace the part.","ReportReceivedDate":"01/01/2021"}]}
      """;
  static final String VARIANTS = """
      {"Results":[{"VehicleId":101,"VehicleDescription":"Test FWD"},{"VehicleId":102,"VehicleDescription":"Test AWD"}]}
      """;
  static final String RATING = """
      {"Results":[{"OverallRating":"5","OverallFrontCrashRating":"4","OverallSideCrashRating":"Not Rated",
      "RolloverRating":"3","RolloverPossibility":0.175}]}
      """;
  HttpServer server;
  final List<String> paths = new CopyOnWriteArrayList<>();
  @AfterEach void stop() { if (server != null) server.stop(0); }
  record Answer(int status, String body) {}
  NhtsaVehicleSafetyProvider serve(Function<String, Answer> answer) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      String path = exchange.getRequestURI().toString(); paths.add(path);
      var result = answer.apply(path);
      if (result.status() == 302) exchange.getResponseHeaders().add("Location", "/redirect-target");
      var bytes = result.body().getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(result.status(), bytes.length);
      try (var output = exchange.getResponseBody()) { output.write(bytes); }
      catch (java.io.IOException closed) { /* The adapter closes oversized responses early. */ }
    });
    server.start();
    return adapter("http://127.0.0.1:" + server.getAddress().getPort());
  }
  static NhtsaVehicleSafetyProvider adapter(String url) {
    return new NhtsaVehicleSafetyProvider(url, Duration.ofSeconds(2), 2, Duration.ZERO, new ObjectMapper());
  }
  @Test void preservesEveryRecallFieldAndAllNamedVariantsAndSendsOnlyModelIdentity() throws Exception {
    var result = serve(path -> new Answer(200, path.startsWith("/recalls") ? RECALL
        : path.contains("VehicleId") ? RATING : VARIANTS)).retrieve(CAR);
    assertThat(result.source()).isEqualTo("NHTSA");
    assertThat(result.status()).isEqualTo("AVAILABLE");
    assertThat(result.retrievedAt()).isNotNull();
    assertThat(result.recalls()).containsExactly(new VehicleSafetyProvider.Recall("21V001000", "AIR BAGS",
        "A model recall.", "An injury risk.", "Replace the part.", "01/01/2021"));
    assertThat(result.ratings()).extracting(VehicleSafetyProvider.Rating::name).containsExactly("Test FWD", "Test AWD");
    assertThat(result.ratings().getFirst().overall()).isEqualTo("5");
    assertThat(result.ratings().getFirst().side()).isNull();
    assertThat(result.ratings().getFirst().rolloverRisk()).isEqualTo("0.175");
    assertThat(paths).containsExactly(
        "/recalls/recallsByVehicle?make=Test%20Make&model=A%2FB%20%26%20C&modelYear=2021",
        "/SafetyRatings/modelyear/2021/make/Test%20Make/model/A%2FB%20%26%20C?format=json",
        "/SafetyRatings/VehicleId/101?format=json", "/SafetyRatings/VehicleId/102?format=json");
  }
  @Test void emptyResultsAreAvailableAndDistinctFromFailure() throws Exception {
    var result = serve(path -> new Answer(200, "{\"Results\":[]}")).retrieve(CAR);
    assertThat(result.status()).isEqualTo("AVAILABLE");
    assertThat(result.recalls()).isEmpty(); assertThat(result.ratings()).isEmpty();
  }
  @Test void rateLimitThenSuccessIsRetried() throws Exception {
    var calls = new AtomicInteger();
    var result = serve(path -> new Answer(calls.getAndIncrement() == 0 ? 429 : 200, "{\"Results\":[]}")).retrieve(CAR);
    assertThat(result.status()).isEqualTo("AVAILABLE"); assertThat(paths).hasSize(3);
  }
  @Test void serverFailureExhaustsBound() throws Exception {
    var provider = serve(path -> new Answer(503, "{}"));
    assertThatThrownBy(() -> provider.retrieve(CAR)).hasMessage("NHTSA did not answer");
    assertThat(paths).hasSize(2);
  }
  @Test void oversizedBodyIsUnavailable() throws Exception {
    var provider = serve(path -> new Answer(200, "x".repeat(NhtsaVehicleSafetyProvider.MAX_BODY_BYTES + 1)));
    assertThatThrownBy(() -> provider.retrieve(CAR)).hasMessage("NHTSA did not answer");
    assertThat(paths).hasSize(1);
  }
  @Test void malformedAndMissingResultsAreUnavailable() throws Exception {
    var provider = serve(path -> new Answer(200, "not json"));
    assertThatThrownBy(() -> provider.retrieve(CAR)).hasMessage("NHTSA did not answer");
    stop(); paths.clear();
    var missing = serve(path -> new Answer(200, "{}"));
    assertThatThrownBy(() -> missing.retrieve(CAR)).hasMessage("NHTSA did not answer");
  }
  @Test void redirectsAreNotFollowedOrRetried() throws Exception {
    var provider = serve(path -> new Answer(302, "{}"));
    assertThatThrownBy(() -> provider.retrieve(CAR)).hasMessage("NHTSA did not answer");
    assertThat(paths).hasSize(1);
  }
  @Test void failedVariantDoesNotProduceAnEmptyAvailableAnswer() throws Exception {
    var provider = serve(path -> new Answer(200, path.startsWith("/recalls") ? RECALL
        : path.contains("VehicleId") ? "{\"Results\":[]}" : VARIANTS));
    assertThatThrownBy(() -> provider.retrieve(CAR)).hasMessage("NHTSA did not answer");
  }
  @Test void aStalledBodyIsBoundedByTheRequestDeadline() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      paths.add(exchange.getRequestURI().toString());
      exchange.sendResponseHeaders(200, 100);
      try { Thread.sleep(800); }
      catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
      exchange.close();
    });
    server.start();
    var provider = new NhtsaVehicleSafetyProvider("http://127.0.0.1:" + server.getAddress().getPort(),
        Duration.ofMillis(100), 2, Duration.ZERO, new ObjectMapper());
    long started = System.nanoTime();
    assertThatThrownBy(() -> provider.retrieve(CAR)).hasMessage("NHTSA did not answer");
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(700));
  }
  @Test void configurationAndDeploymentAreBounded() {
    assertThatThrownBy(() -> adapter("http://external.example")).hasMessageContaining("HTTPS");
    assertThatThrownBy(() -> new NhtsaVehicleSafetyProvider("https://api.nhtsa.gov", Duration.ZERO, 2, Duration.ZERO, new ObjectMapper()));
    assertThatThrownBy(() -> new NhtsaVehicleSafetyProvider("https://api.nhtsa.gov", Duration.ofSeconds(1), 4, Duration.ZERO, new ObjectMapper()));
    var fake = new FakeVehicleSafetyProvider();
    for (String stage : List.of("pilot", "production", "prod", " PROD ")) {
      var env = new MockEnvironment().withProperty("evidence.deployment", stage);
      assertThatThrownBy(() -> new VehicleSafetyProductionGuard(env, fake).afterSingletonsInstantiated()).hasMessageContaining("fake");
    }
    var env = new MockEnvironment(); env.setActiveProfiles("pilot");
    assertThatThrownBy(() -> new VehicleSafetyProductionGuard(env, fake).afterSingletonsInstantiated());
    new VehicleSafetyProductionGuard(env, adapter("https://api.nhtsa.gov")).afterSingletonsInstantiated();
    new VehicleSafetyProductionGuard(new MockEnvironment(), fake).afterSingletonsInstantiated();
  }
}

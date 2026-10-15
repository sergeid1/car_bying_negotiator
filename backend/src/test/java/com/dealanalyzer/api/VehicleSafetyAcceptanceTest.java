package com.dealanalyzer.api;

import static com.dealanalyzer.api.HttpAcceptanceHarness.*;
import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

class VehicleSafetyAcceptanceTest {
  @TempDir Path directory;
  @Test void nhtsaCallsAreMeteredAndOutagesRemainDistinctFromEmptyResults() throws Exception {
    var failed = new java.util.concurrent.atomic.AtomicBoolean();
    var paths = new java.util.concurrent.CopyOnWriteArrayList<String>();
    var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      paths.add(exchange.getRequestURI().toString());
      var bytes = "{\"Results\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(failed.get() ? 503 : 200, bytes.length);
      try (var output = exchange.getResponseBody()) { output.write(bytes); }
    });
    server.start();
    try (var app = new HttpAcceptanceHarness(directory).start("nhtsa-meter", false,
        "--vehicle-safety.provider=nhtsa", "--vehicle-safety.nhtsa.retry-delay=PT0S",
        "--vehicle-safety.nhtsa.base-url=http://127.0.0.1:" + server.getAddress().getPort())) {
      var created = post(app.client(), "/api/v1/cases", CasePortabilityAcceptanceTest.buyingCase());
      String id = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      String base = "/api/v1/cases/" + id;
      post(app.client(), base + "/vehicle-evidence", Map.of("confirmed", true, "manualFacts",
          Map.of("year", 2021, "make", "Honda", "model", "Accord", "mileage", 10000)), "car", token);
      var result = get(app.client(), base + "/vehicle-safety", token).path("facts");
      assertThat(result.path("status").asText()).isEqualTo("AVAILABLE");
      assertThat(result.path("recalls")).isEmpty();
      var jdbc = app.context().getBean(JdbcTemplate.class);
      var row = jdbc.queryForMap("select * from provider_usage where case_id = ?", id);
      assertThat(row.get("provider")).isEqualTo("NHTSA");
      assertThat(row.get("provider_version")).isEqualTo("nhtsa-vehicle-safety.v1");
      assertThat(row.get("license")).isEqualTo("PUBLIC_US_GOVERNMENT_DATA");
      assertThat(row.get("status")).isEqualTo("SUCCESS");
      assertThat((java.math.BigDecimal) row.get("cost_usd")).isZero();
      assertThat(row.get("query_json")).isNull();
      failed.set(true);
      post(app.client(), base + "/vehicle-evidence", Map.of("confirmed", true, "manualFacts",
          Map.of("year", 2022, "make", "Honda", "model", "Accord", "mileage", 10000)), "car-two", token);
      assertThat(get(app.client(), base + "/vehicle-safety", token).at("/facts/status").asText()).isEqualTo("UNAVAILABLE");
      assertThat(jdbc.queryForObject("select count(*) from provider_usage where case_id = ? and status = 'PROVIDER_FAILURE'", Integer.class, id)).isEqualTo(1);
      assertThat(paths).hasSize(4).allSatisfy(path -> assertThat(path).doesNotContain(id, token, "vin"));
    } finally { server.stop(0); }
  }
  @Test void authenticatesCachesFreezesAcrossRestartExportsAndDeletesWithTheCase() throws Exception {
    var harness = new HttpAcceptanceHarness(directory);
    String id, token;
    com.fasterxml.jackson.databind.JsonNode report;
    try (var app = harness.start("safety", false, "--case.deletion.delay=PT1H",
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef")) {
      var created = post(app.client(), "/api/v1/cases", CasePortabilityAcceptanceTest.buyingCase());
      id = created.at("/case/id").asText(); token = created.path("accessToken").asText();
      String base = "/api/v1/cases/" + id;
      assertThat(get(app.client(), base + "/vehicle-safety", token).at("/facts/status").asText()).isEqualTo("MISSING_VEHICLE");
      assertThatThrownBy(() -> get(app.client(), base + "/vehicle-safety", "wrong"))
          .isInstanceOf(org.springframework.web.client.HttpClientErrorException.NotFound.class);
      post(app.client(), base + "/vehicle-evidence", Map.of("confirmed", true, "manualFacts",
          Map.of("year", 2021, "make", "Honda", "model", "Accord", "mileage", 10000)), "vehicle", token);
      var safety = get(app.client(), base + "/vehicle-safety", token);
      assertThat(safety.at("/facts/status").asText()).isEqualTo("AVAILABLE");
      assertThat(safety.at("/facts/source").asText()).contains("Development sample");
      assertThat(get(app.client(), base + "/vehicle-safety", token)).isEqualTo(safety);
      post(app.client(), base + "/analyses", Map.of(), "report", token);
      report = get(app.client(), base + "/reports/1", token);
      assertThat(report.path("vehicleSafety")).isEqualTo(safety);
      var jdbc = app.context().getBean(JdbcTemplate.class);
      var usage = jdbc.queryForMap("select * from provider_usage where case_id = ? and operation = 'VEHICLE_SAFETY'", id);
      assertThat(usage.get("status")).isEqualTo("SUCCESS");
      assertThat(usage.get("provider_version")).isEqualTo("fake-vehicle-safety.v1");
      assertThat(usage.get("query_json")).isNull();
      var export = post(app.client(), base + "/exports", Map.of("format", "HTML", "reportVersion", 1), "html", token);
      String html = app.client().get().uri(base + "/exports/" + export.path("id").asText())
          .header("Case-Access-Token", token).retrieve().body(String.class);
      assertThat(html).contains("Recalls and safety ratings", "SAMPLE-116", "repair record", safety.at("/facts/retrievedAt").asText());
      var renderer = app.context().getBean(com.dealanalyzer.report.StoredReportRenderer.class);
      try (var pdf = org.apache.pdfbox.Loader.loadPDF(renderer.pdf(report))) {
        assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(pdf)).contains("SAMPLE-116", "repair record", "Sample variant");
      }
      // New reports reflect a newly confirmed model; the earlier version keeps its original facts.
      post(app.client(), base + "/vehicle-evidence", Map.of("confirmed", true, "manualFacts",
          Map.of("year", 2022, "make", "Toyota", "model", "Camry", "mileage", 10000)), "changed-car", token);
      post(app.client(), base + "/analyses", Map.of(), "report-two", token);
      assertThat(get(app.client(), base + "/reports/1", token)).isEqualTo(report);
      assertThat(get(app.client(), base + "/reports/2", token).at("/vehicleSafety/facts/vehicle/model").asText()).isEqualTo("Camry");
    }
    try (var app = harness.start("safety", false, "--case.deletion.delay=PT1H",
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef")) {
      String base = "/api/v1/cases/" + id;
      assertThat(get(app.client(), base + "/reports/1", token)).isEqualTo(report);
      post(app.client(), base + "/deletion", Map.of(), "delete", token);
      app.context().getBean(com.dealanalyzer.buyingcase.persistence.CaseDeletionWorker.class).runOnce();
      var jdbc = app.context().getBean(JdbcTemplate.class);
      assertThat(jdbc.queryForObject("select count(*) from analysis_reports where case_id = ?", Integer.class, id)).isZero();
      assertThat(jdbc.queryForObject("select count(*) from provider_usage where case_id = ?", Integer.class, id)).isZero();
      assertThatThrownBy(() -> get(app.client(), base + "/vehicle-safety", token))
          .isInstanceOf(org.springframework.web.client.HttpClientErrorException.NotFound.class);
    }
  }
}

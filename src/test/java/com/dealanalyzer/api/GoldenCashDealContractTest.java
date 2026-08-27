package com.dealanalyzer.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.dealanalyzer.DealAnalyzerApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

class GoldenCashDealContractTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path temporaryDirectory;

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {
      "/fixtures/golden/cash-deal-standard.json",
      "/fixtures/golden/cash-deal-no-extras.json"
  })
  void frozenCashDealProducesAuditableCalculationContract(String fixtureResource) throws Exception {
    JsonNode fixture = JSON.readTree(getClass().getResourceAsStream(
        fixtureResource));
    JsonNode expected = fixture.get("expected");

    try (RunningApplication application = start(fixture.get("name").asText())) {
      JsonNode started = post(application.client(), fixture.get("input"));
      awaitCompleted(application.client(), started.at("/operation/id").asText());
      JsonNode report = get(application.client(), "/api/v1/cases/"
          + started.at("/case/id").asText() + "/reports/latest");

      assertThat(report.at("/analysis/economics/sellingPrice/minorUnits").asLong())
          .isEqualTo(expected.get("sellingPriceMinor").asLong());
      assertThat(report.at("/analysis/economics/dealerFees/minorUnits").asLong())
          .isEqualTo(expected.get("dealerFeesMinor").asLong());
      assertThat(report.at("/analysis/economics/addOns/minorUnits").asLong())
          .isEqualTo(expected.get("addOnsMinor").asLong());
      assertThat(report.at("/analysis/economics/taxes/minorUnits").asLong())
          .isEqualTo(expected.get("taxesMinor").asLong());
      assertThat(report.at("/analysis/economics/governmentCharges/minorUnits").asLong())
          .isEqualTo(expected.get("governmentChargesMinor").asLong());
      assertThat(report.at("/analysis/economics/outTheDoorTotal/minorUnits").asLong())
          .isEqualTo(expected.get("outTheDoorTotalMinor").asLong());
      assertThat(report.at("/analysis/economics/calculationVersion").asText())
          .isEqualTo(expected.get("calculationVersion").asText());
      assertThat(report.at("/analysis/economics/rounding"))
          .isEqualTo(expected.get("rounding"));
      assertThat(report.at("/analysis/economics/steps"))
          .isEqualTo(expected.get("steps"));
      assertThat(report.at("/analysis/policyVersions"))
          .isEqualTo(expected.get("policyVersions"));
      assertThat(report.get("schemaVersion").asText())
          .isEqualTo(expected.get("reportSchemaVersion").asText());
    }
  }

  private RunningApplication start(String fixtureName) {
    String dataSourceUrl = "jdbc:h2:file:"
        + temporaryDirectory.resolve("golden-contract-" + fixtureName.hashCode()).toAbsolutePath()
        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE";
    ConfigurableApplicationContext context =
        new SpringApplicationBuilder(DealAnalyzerApplication.class)
            .web(WebApplicationType.SERVLET)
            .run(
                "--server.port=0",
                "--spring.datasource.url=" + dataSourceUrl,
                "--spring.task.execution.pool.core-size=1");
    int port = ((WebServerApplicationContext) context).getWebServer().getPort();
    return new RunningApplication(context, RestClient.create("http://localhost:" + port));
  }

  private static JsonNode post(RestClient client, JsonNode body) throws Exception {
    String response = client.post()
        .uri("/api/v1/cases/synthetic")
        .contentType(MediaType.APPLICATION_JSON)
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .body(body)
        .retrieve()
        .body(String.class);
    return JSON.readTree(response);
  }

  private static JsonNode awaitCompleted(RestClient client, String operationId) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    JsonNode operation;
    do {
      operation = get(client, "/api/v1/operations/" + operationId);
      if (operation.get("stage").asText().equals("COMPLETE")) {
        return operation;
      }
      Thread.sleep(25);
    } while (System.nanoTime() < deadline);
    throw new AssertionError("Operation did not complete: " + operation);
  }

  private static JsonNode get(RestClient client, String path) throws Exception {
    return JSON.readTree(client.get().uri(path).retrieve().body(String.class));
  }

  private record RunningApplication(ConfigurableApplicationContext context, RestClient client)
      implements AutoCloseable {
    @Override
    public void close() {
      context.close();
    }
  }
}

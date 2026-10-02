package com.dealanalyzer.api;

import static com.dealanalyzer.api.HttpAcceptanceHarness.*;
import static org.assertj.core.api.Assertions.assertThat;
import com.dealanalyzer.buyingcase.SyntheticAnalysisRequest.VehicleInput;
import com.dealanalyzer.market.*;
import com.dealanalyzer.listing.ListingEvidenceProvider;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

class ProviderReadyEvidenceAcceptanceTest {
  @TempDir Path directory;

  @Test void unavailableAndThinEvidenceStayExplicitInSavedReportsAndCallsHaveLatency() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("provider-ready", false,
        RecordedProvider.class, "--operations.accounts.support.token=" + ServiceOperationsAcceptanceTest.SUPPORT,
        "--operations.accounts.support.role=SUPPORT")) {
      var created = post(app.client(), "/api/v1/cases", CasePortabilityAcceptanceTest.buyingCase());
      String id = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      String path = "/api/v1/cases/" + id;
      post(app.client(), path + "/vehicle-evidence", Map.of("confirmed", true,
          "manualFacts", Map.of("year", 2021, "make", "Honda", "model", "Accord", "trim", "EX-L", "mileage", 38000),
          "listingUrl", "https://licensed.example/vehicles/1HGCM82633A004352"), "vehicle", token);
      var provider = app.context().getBean(RecordedProvider.class);
      for (String mode : List.of("thin", "unavailable", "schema", "missing", "stale", "throw")) {
        provider.mode = mode;
        var assessment = post(app.client(), path + "/market-assessments", Map.of(), "market-" + mode, token);
        assertThat(assessment.path("status").asText()).isEqualTo(mode.equals("thin") ? "INSUFFICIENT_DATA" : "UNAVAILABLE");
        assertThat(assessment.path("range").isNull()).isTrue();
        post(app.client(), path + "/analyses", Map.of(), "report-" + mode, token);
        var report = get(app.client(), path + "/reports/latest", token);
        assertThat(report.at("/analysis/dealRating").asText()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(report.at("/analysis/marketAvailability").asText()).isEqualTo("UNAVAILABLE");
        String wording = report.at("/analysis/marketEvidence/paragraphs").toString();
        assertThat(wording).contains("No market rating is drawn");
        assertThat(wording).contains(switch (mode) {
          case "thin" -> "Too few";
          case "schema" -> "schema drift";
          case "missing" -> "snapshot metadata";
          case "stale" -> "stale";
          case "throw" -> "provider failed";
          default -> "temporarily unavailable";
        });
      }
      for (String mode : List.of("listing-unavailable", "listing-throw")) {
        provider.mode = mode;
        var vehicle = post(app.client(), path + "/vehicle-evidence", Map.of("confirmed", true,
            "manualFacts", Map.of("year", 2021, "make", "Honda", "model", "Accord", "trim", "EX-L", "mileage", 38000),
            "listingUrl", "https://licensed.example/vehicles/1HGCM82633A004352"), mode, token);
        assertThat(vehicle.path("listingSnapshot").isNull()).isTrue();
        assertThat(vehicle.at("/listingRetrieval/status").asText()).isEqualTo("UNAVAILABLE_FALLBACK");
        assertThat(vehicle.at("/listingRetrieval/message").asText()).contains("unavailable");
      }
      var usage = ServiceOperationsAcceptanceTest.opGet(app.client(),
          "/api/v1/operator/cases/" + id + "/usage", ServiceOperationsAcceptanceTest.SUPPORT);
      assertThat(usage).hasSize(9);
      assertThat(usage.findValuesAsText("operation")).contains("MARKET_RETRIEVAL", "LISTING_RETRIEVAL");
      for (var call : usage) {
        assertThat(call.path("unit").asText()).isEqualTo("REQUEST");
        assertThat(call.path("units").asLong()).isEqualTo(1);
        assertThat(Instant.parse(call.path("finishedAt").asText()))
            .isAfterOrEqualTo(Instant.parse(call.path("createdAt").asText()));
      }
      assertThat(usage.findValuesAsText("status"))
          .contains("SUCCESS", "CONTRACT_FAILURE", "PROVIDER_UNAVAILABLE", "PROVIDER_FAILURE");
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class RecordedProvider {
    String mode = "thin";
    @Bean @Primary ListingEvidenceProvider recordedListing(
        @org.springframework.beans.factory.annotation.Qualifier("deterministicListingEvidenceProvider") ListingEvidenceProvider fixture) {
      return url -> {
        if (mode.equals("listing-throw")) throw new IllegalStateException("private vendor payload");
        if (mode.equals("listing-unavailable")) return ListingEvidenceProvider.ListingRetrieval.unavailable(
            "Listing provider is temporarily unavailable; no listing values were used.");
        return fixture.retrieve(url);
      };
    }
    @Bean @Primary MarketDataProvider recordedMarket() {
      return new MarketDataProvider() {
        public MarketEvidence assess(VehicleInput vehicle) { throw new UnsupportedOperationException(); }
        public ProviderResult retrieve(MarketVehicle vehicle) {
          if (mode.equals("throw")) throw new IllegalStateException("private vendor payload");
          Instant now = Instant.now();
          var snapshot = mode.equals("missing") ? null : new ProviderSnapshot("Recorded test source", "v1",
              mode.equals("schema") ? "unexpected.v2" : "market-provider.v1", "recorded test rights", "fixture://test",
              mode.equals("stale") ? now.minus(Duration.ofDays(46)) : now);
          if (mode.equals("unavailable")) return ProviderResult.unavailable("Provider temporarily unavailable.", snapshot);
          return ProviderResult.available(snapshot, List.of(new ComparableObservation(
              "one", "https://example/one", 2021, "Honda", "Accord", "EX-L", 38000L,
              2500000L, "USD", "27604", now.minus(Duration.ofDays(2)))));
        }
      };
    }
  }
}

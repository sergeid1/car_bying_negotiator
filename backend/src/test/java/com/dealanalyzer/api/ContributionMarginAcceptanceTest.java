package com.dealanalyzer.api;

import static com.dealanalyzer.api.HttpAcceptanceHarness.*;
import static com.dealanalyzer.api.ServiceOperationsAcceptanceTest.*;
import static org.assertj.core.api.Assertions.*;

import com.dealanalyzer.pilot.PilotMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.HttpClientErrorException;

class ContributionMarginAcceptanceTest {
  @TempDir Path directory;
  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  private static final String METRICS = "/api/v1/operator/contribution/metrics?from=2020-01-01&to=2100-01-01";
  private String[] settings(boolean priced) {
    var settings = new ArrayList<>(List.of(SETTINGS));
    if (priced) settings.addAll(List.of("--contribution.fees.percentage=3", "--contribution.fees.fixed-minor=30",
        "--contribution.fees.currency=USD", "--contribution.fees.version=test-fees.v1", "--contribution.fees.source=synthetic-test",
        "--contribution.support.usd-per-minute=0.50", "--contribution.support.version=test-support.v1", "--contribution.support.source=synthetic-test"));
    return settings.toArray(String[]::new);
  }
  @Test void paidCaseCostsRefundsSupportAndUnknownCostsSurviveDeletionWithoutIdentifiers() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).start("margin", false, settings(true))) {
      var created = create(app);
      var second = create(app);
      var dev = create(app);
      String id = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      var jdbc = jdbc(app);
      purchase(jdbc, id, 2000, "RECORDED_TEST_PAYMENT");
      purchase(jdbc, second.at("/case/id").asText(), 1000, "RECORDED_TEST_PAYMENT");
      purchase(jdbc, dev.at("/case/id").asText(), 9999, "DEVELOPMENT_NO_CHARGE");
      usage(jdbc, id, "OCR", "1.25"); usage(jdbc, id, "EXTRACTION", "2.00");
      usage(jdbc, id, "MARKET_RETRIEVAL", "0.75"); usage(jdbc, id, "LISTING_RETRIEVAL", "0.50");
      String supportPath = "/api/v1/operator/contribution/cases/" + id + "/support-time";
      var entry = Map.of("entryId", UUID.randomUUID().toString(), "minutes", 10);
      assertThatThrownBy(() -> opPost(app.client(), supportPath, entry, token)).isInstanceOf(HttpClientErrorException.Unauthorized.class);
      assertThatThrownBy(() -> opPost(app.client(), supportPath, entry, REVIEWER)).isInstanceOf(HttpClientErrorException.Forbidden.class);
      assertThat(opPost(app.client(), supportPath, entry, SUPPORT)).isEqualTo(opPost(app.client(), supportPath, entry, SUPPORT));
      assertThatThrownBy(() -> opPost(app.client(), supportPath, Map.of("entryId", entry.get("entryId"), "minutes", 11), SUPPORT))
          .isInstanceOf(HttpClientErrorException.Conflict.class);
      assertThatThrownBy(() -> opPost(app.client(), supportPath, Map.of("entryId", UUID.randomUUID().toString(), "minutes", 0), SUPPORT))
          .isInstanceOf(HttpClientErrorException.BadRequest.class);
      var before = report(app);
      assertThat(before.at("/paidPurchases/numerator").asInt()).isEqualTo(2);
      assertThat(before.at("/paidPurchases/denominator").asInt()).isEqualTo(3);
      assertThat(before.at("/developmentNoChargePurchases/numerator").asInt()).isEqualTo(1);
      assertThat(before.at("/recordedPaidCaseCosts/revenueUsd").decimalValue()).isEqualByComparingTo("30");
      assertThat(before.at("/recordedPaidCaseCosts/knownProviderAndExtractionCostUsd").decimalValue()).isEqualByComparingTo("4.50");
      assertThat(before.at("/paymentFees/sumUsd").decimalValue()).isEqualByComparingTo("1.50");
      // Margins 9.60 and 9.40, mean and continuous median 9.50.
      assertThat(before.at("/contributionMargin/count").asInt()).isEqualTo(2);
      assertThat(before.at("/contributionMargin/sumUsd").decimalValue()).isEqualByComparingTo("19.00");
      assertThat(before.at("/contributionMargin/meanUsd").decimalValue()).isEqualByComparingTo("9.50");
      assertThat(before.at("/contributionMargin/medianUsd").decimalValue()).isEqualByComparingTo("9.50");
      assertThat(before.toString()).doesNotContain(id, token, "entryId", "caseId", "purchaseId", "VIN", "documentId");
      var entity = app.client().get().uri(METRICS).header("Authorization", "Bearer " + SUPPORT).retrieve().toEntity(String.class);
      assertThat(entity.getHeaders().getCacheControl()).isEqualTo("no-store");
      assertThatThrownBy(() -> get(app.client(), METRICS)).isInstanceOf(HttpClientErrorException.Unauthorized.class);
      assertThatThrownBy(() -> report(app, REVIEWER)).isInstanceOf(HttpClientErrorException.Forbidden.class);
      assertThatThrownBy(() -> report(app, token)).isInstanceOf(HttpClientErrorException.Unauthorized.class);
      opPost(app.client(), "/api/v1/operator/purchases/" + id + "/refunds", Map.of(), SUPPORT);
      var refunded = report(app);
      assertThat(refunded.at("/refunds/numerator").asInt()).isEqualTo(1);
      assertThat(refunded.at("/refunds/denominator").asInt()).isEqualTo(2);
      assertThat(refunded.at("/recordedPaidCaseCosts/refundUsd").decimalValue()).isEqualByComparingTo("20");
      assertThat(refunded.at("/contributionMargin/sumUsd").decimalValue()).isEqualByComparingTo("-1.00");
      // Null cost is still a billed/possibly billed call, never a free operation.
      usage(jdbc, id, "OCR", null);
      var unknown = report(app);
      assertThat(unknown.at("/contributionMargin/status").asText()).isEqualTo("NOT_MEASURABLE");
      assertThat(unknown.at("/contributionMargin/notMeasurableCount").asInt()).isEqualTo(1);
      assertThat(unknown.at("/contributionMargin/reasons/UNKNOWN_PROVIDER_COST").asInt()).isEqualTo(1);
      assertThat(unknown.at("/contributionMargin/sumUsd").decimalValue()).isEqualByComparingTo("9.40");
      post(app.client(), "/api/v1/cases/" + id + "/deletion", Map.of(), "delete", token);
      app.context().getBean(com.dealanalyzer.buyingcase.persistence.CaseDeletionWorker.class).runOnce();
      assertThat(report(app)).isEqualTo(unknown);
      // Identical removed cases are one weighted anonymous bin, not case-addressable rows.
      var twin = create(app);
      purchase(jdbc, twin.at("/case/id").asText(), 1000, "RECORDED_TEST_PAYMENT");
      var beforeGroupedRemoval = report(app);
      for (var caseToRemove : List.of(second, twin)) {
        post(app.client(), "/api/v1/cases/" + caseToRemove.at("/case/id").asText() + "/deletion", Map.of(), "delete", caseToRemove.path("accessToken").asText());
        app.context().getBean(com.dealanalyzer.buyingcase.persistence.CaseDeletionWorker.class).runOnce();
      }
      assertThat(report(app)).isEqualTo(beforeGroupedRemoval);
      assertThat(jdbc.queryForObject("select count(*) from contribution_totals", Long.class)).isEqualTo(2);
      assertThat(jdbc.queryForObject("select case_count from contribution_totals where unknown_calls = 0", Long.class)).isEqualTo(2);
      assertThat(beforeGroupedRemoval.at("/contributionMargin/count").asInt()).isEqualTo(2);
      assertThat(beforeGroupedRemoval.at("/contributionMargin/medianUsd").decimalValue()).isEqualByComparingTo("9.40");
      assertThat(jdbc.queryForObject("select count(*) from case_support_time", Long.class)).isZero();
      assertThat(jdbc.queryForObject("select count(*) from provider_usage where case_id = ?", Long.class, id)).isZero();
      assertThat(jdbc.queryForObject("select count(*) from contribution_totals", Long.class)).isEqualTo(2);
      assertThatThrownBy(() -> opPost(app.client(), supportPath, entry, SUPPORT)).isInstanceOf(HttpClientErrorException.NotFound.class);
      app.context().getBean(com.dealanalyzer.buyingcase.persistence.CaseDeletionWorker.class).runOnce();
      assertThat(report(app)).isEqualTo(beforeGroupedRemoval);
    }
  }

  @Test void verifiedOffersAndPersistedReportsCountOnceAcrossVersionsAndRemoval() throws Exception {
    var settings = new ArrayList<>(List.of(settings(true)));
    settings.add("--document.storage.path=" + directory.resolve("objects"));
    settings.add("--document.storage.encryption-key=0123456789abcdef0123456789abcdef");
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("verified-margin", true,
        DocumentExtractionAcceptanceTest.FixtureExtractionProvider.class, settings.toArray(String[]::new))) {
      var created = create(app);
      String id = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      String base = "/api/v1/cases/" + id;
      post(app.client(), base + "/vehicle-evidence", Map.of("confirmed", true, "manualFacts",
          Map.of("year", 2021, "make", "Honda", "model", "Accord", "trim", "EX-L", "mileage", 38000)), "vehicle", token);
      var parts = new org.springframework.util.LinkedMultiValueMap<String, Object>();
      parts.add("file", new org.springframework.core.io.ByteArrayResource(DocumentExtractionAcceptanceTest.supportedDealerQuotePdf()) {
        @Override public String getFilename() { return "quote.pdf"; }
      });
      var upload = JSON.readTree(app.client().post().uri(base + "/documents").header("Case-Access-Token", token)
          .header("Idempotency-Key", "upload").contentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA)
          .body(parts).retrieve().body(String.class));
      String documentId = upload.at("/document/id").asText();
      long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
      JsonNode operation;
      do {
        operation = get(app.client(), "/api/v1/operations/" + upload.at("/operation/id").asText(), token);
        if (Set.of("EXTRACTION_COMPLETE", "FAILED").contains(operation.path("stage").asText())) break;
        Thread.sleep(200);
      } while (System.nanoTime() < deadline);
      assertThat(operation.path("stage").asText()).isEqualTo("EXTRACTION_COMPLETE");
      JsonNode extraction = get(app.client(), base + "/documents/" + documentId + "/extractions/latest", token);
      for (var candidate : extraction.path("candidates")) {
        if (candidate.path("field").asText().equals("AMOUNT_FINANCED")) {
          post(app.client(), base + "/documents/" + documentId + "/extractions/" + extraction.path("id").asText() + "/corrections",
              Map.of("targetType", "CANDIDATE", "targetId", candidate.path("id").asText(), "normalizedValue", "2680000", "normalizedUnit", "USD_MINOR"), "correction", token);
        }
      }
      var candidates = new ArrayList<String>();
      extraction.path("candidates").forEach(candidate -> candidates.add(candidate.path("id").asText()));
      var lines = new ArrayList<String>();
      extraction.path("lineItems").forEach(line -> lines.add(line.path("id").asText()));
      var confirmation = Map.of("extractionId", extraction.path("id").asText(), "confirmedCandidateIds", candidates, "confirmedLineItemIds", lines);
      post(app.client(), base + "/dealer-offers", confirmation, "verify-one", token);
      post(app.client(), base + "/dealer-offers", confirmation, "verify-two", token);
      // Report persistence is the completion evidence; versions do not multiply cases.
      var jdbc = jdbc(app);
      jdbc.update("insert into analysis_reports (case_id, report_version, payload_json, analysis_fingerprint, created_at) values (?, 1, '{}', 'test', current_timestamp)", id);
      jdbc.update("insert into analysis_reports (case_id, report_version, payload_json, analysis_fingerprint, created_at) values (?, 2, '{}', 'test', current_timestamp)", id);
      var before = report(app);
      assertThat(before.at("/casesWithVerifiedOffer/numerator").asInt()).isEqualTo(1);
      assertThat(before.at("/casesWithVerifiedOffer/denominator").asInt()).isEqualTo(1);
      assertThat(before.at("/completedReports/numerator").asInt()).isEqualTo(1);
      assertThat(before.at("/completedReports/denominator").asInt()).isEqualTo(1);
      CaseDeletionAcceptanceTest.verifyCompleteRemoval(app, id, token, directory.resolve("objects"));
      assertThat(report(app)).isEqualTo(before);
    }
  }

  @Test void missingPricingEmptyWindowHalfOpenCohortAndSurveyDoNotCreateRevenue() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).start("unpriced-margin", false, settings(false))) {
      var empty = report(app);
      assertThat(empty.at("/caseStarts").asInt()).isZero();
      assertThat(empty.at("/paidPurchases/rate").isNull()).isTrue();
      assertThat(empty.at("/paymentFees/status").asText()).isEqualTo("UNAVAILABLE");
      assertThat(empty.at("/contributionMargin/status").asText()).isEqualTo("UNAVAILABLE");
      assertThat(empty.at("/contributionMargin/sumUsd").isNull()).isTrue();
      var created = create(app);
      String id = created.at("/case/id").asText();
      var jdbc = jdbc(app);
      jdbc.update("update analysis_cases set created_at = timestamp with time zone '2020-01-02 00:00:00+00' where id = ?", id);
      assertThat(jdbc.queryForObject("select cohort_day from case_contribution_facts where case_id = ?", java.sql.Date.class, id).toLocalDate())
          .isEqualTo(java.time.LocalDate.of(2020, 1, 2));
      jdbc.update("insert into experiment_assignments values (?, ?, 'A', 2000, true, 'test', current_timestamp)", UUID.randomUUID().toString(), id);
      jdbc.update("insert into outcome_feedback (id, case_id, stage, willing_to_pay, payload_json, created_at) values (?, ?, 'FINAL', true, '{}', current_timestamp)", UUID.randomUUID().toString(), id);
      var survey = JSON.valueToTree(app.context().getBean(PilotMetrics.class).snapshot());
      assertThat(survey.path("willingnessToPay")).hasSize(1);
      var withSurvey = report(app);
      assertThat(withSurvey.at("/paidPurchases/numerator").asInt()).isZero();
      assertThat(withSurvey.at("/qualifiedCasesStarted/numerator").asInt()).isEqualTo(1);
      assertThat(withSurvey.toString()).doesNotContain("willingness", "willingToPay", "variant");
      var excluded = operatorGet(app, METRICS.replace("2100-01-01", "2020-01-02"), SUPPORT);
      assertThat(excluded.path("caseStarts").asInt()).isZero();
      assertThat(operatorGet(app, METRICS.replace("2020-01-01", "2020-01-02"), SUPPORT).path("caseStarts").asInt()).isEqualTo(1);
      purchase(jdbc, id, 2000, "RECORDED_TEST_PAYMENT");
      var unpriced = report(app);
      assertThat(unpriced.at("/contributionMargin/notMeasurableCount").asInt()).isEqualTo(1);
      assertThat(unpriced.at("/contributionMargin/reasons/NO_VALID_FEE_SCHEDULE").asInt()).isEqualTo(1);
      assertThat(unpriced.at("/contributionMargin/reasons/NO_VALID_SUPPORT_RATE").asInt()).isEqualTo(1);
      assertThat(unpriced.at("/contributionMargin/sumUsd").isNull()).isTrue();
      // Removing survey answers changes only willingnessToPay, never conversion.
      jdbc.update("delete from outcome_feedback");
      assertThat(report(app)).isEqualTo(unpriced);
      assertThat(JSON.valueToTree(app.context().getBean(PilotMetrics.class).snapshot()).path("willingnessToPay")).isEmpty();
      assertThatThrownBy(() -> operatorGet(app, METRICS.replace("2100-01-01", "2019-01-01"), SUPPORT))
          .isInstanceOf(HttpClientErrorException.BadRequest.class);
      jdbc.update("update purchase_totals set removed_case_count = 1, removed_paid_count = 1 where id = 1");
      var historical = report(app);
      assertThat(historical.at("/historicalDeletionCoverage/status").asText()).isEqualTo("UNAVAILABLE");
      assertThat(historical.at("/historicalDeletionCoverage/removedCasesWithoutWindowRecords").asInt()).isEqualTo(1);
      assertThat(historical.path("paidPurchases")).isEqualTo(unpriced.path("paidPurchases"));
    }
  }
  private JsonNode create(RunningApplication app) throws Exception { return post(app.client(), "/api/v1/cases", PilotFeedbackAcceptanceTest.buyingCase()); }
  private JdbcTemplate jdbc(RunningApplication app) { return new JdbcTemplate(app.context().getBean(javax.sql.DataSource.class)); }
  private void purchase(JdbcTemplate jdbc, String id, long amount, String checkout) {
    jdbc.update("insert into case_purchases (id, case_id, request_key, offer_id, amount_minor, currency, status, checkout, created_at) values (?, ?, 'test', 'test-offer', ?, 'USD', 'PAID', ?, current_timestamp)", id, id, amount, checkout);
  }
  private void usage(JdbcTemplate jdbc, String id, String operation, String cost) {
    jdbc.update("insert into provider_usage (id, case_id, operation, provider, provider_version, model_version, schema_version, license, unit, units, cost_usd, status, created_at) values (?, ?, ?, 'test', 'v1', 'v1', 'v1', 'test', 'CALL', 1, ?, 'SUCCESS', current_timestamp)", UUID.randomUUID().toString(), id, operation, cost == null ? null : new java.math.BigDecimal(cost));
  }
  private JsonNode report(RunningApplication app) throws Exception { return report(app, SUPPORT); }
  private JsonNode report(RunningApplication app, String token) throws Exception { return operatorGet(app, METRICS, token); }
  private JsonNode operatorGet(RunningApplication app, String path, String token) throws Exception {
    return JSON.readTree(app.client().get().uri(path).header("Authorization", "Bearer " + token).retrieve().body(String.class));
  }
}

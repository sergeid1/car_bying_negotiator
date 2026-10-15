package com.dealanalyzer.api;

import static com.dealanalyzer.api.HttpAcceptanceHarness.*;
import static org.assertj.core.api.Assertions.*;
import com.dealanalyzer.insurance.quote.*;
import com.dealanalyzer.insurance.quote.InsuranceApplication.*;
import com.dealanalyzer.account.AccountJourneyAccess;
import com.dealanalyzer.operations.ProviderMeter;
import com.dealanalyzer.operations.ProviderUsageStore;
import com.dealanalyzer.recovery.RecoveryEmail;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/** Exercises real migrations and ProviderUsageStore transactions; no insurance HTTP endpoint needed. */
class InsuranceQuoteMeteringAcceptanceTest {
  @TempDir Path directory;
  static final CopyOnWriteArrayList<String> links = new CopyOnWriteArrayList<>();
  @TestConfiguration static class EmailConfiguration {
    @Bean @Primary RecoveryEmail insuranceEmail() { return (recipient, link) -> links.add(link); }
  }
  @Test void eachCaseScopedRequestPersistsPartnerLicenceResultLatencyAndCostWithoutApplicationDetails() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).start("insurance-quote-meter", false)) {
      var created = post(app.client(), "/api/v1/cases", CasePortabilityAcceptanceTest.buyingCase());
      String id = created.at("/case/id").asText();
      var application = new InsuranceApplication(List.of(new Driver(41, 22, 0, 0)), new Garaging("33602", "FL"),
          new InsuredVehicle("1HGCM82633A004352", 2023, "Honda", "Accord", "EX", 12000),
          CoverageSet.of(new Coverage(Coverage.Type.BODILY_INJURY_LIABILITY, 1000000L, 2000000L, null),
              new Coverage(Coverage.Type.COLLISION, null, null, 50000L)),
          new CurrentPolicy("Current Insurer", null, 91000, Term.SIX_MONTHS));
      var consent = new InsuranceQuoteConsent("synthetic-partner-consent.v1", Instant.now());
      var scope = InsuranceQuoteScope.forCase(id);
      var result = app.context().getBean(InsuranceQuoteService.class).request(scope, application, consent);
      assertThat(result.insurers()).hasSize(5);
      var jdbc = app.context().getBean(JdbcTemplate.class);
      var row = jdbc.queryForMap("select * from provider_usage where case_id = ? and operation = 'INSURANCE_QUOTE'", id);
      assertThat(row.get("provider")).isEqualTo(result.provider().name());
      assertThat(row.get("provider_version")).isEqualTo(result.provider().version());
      assertThat(row.get("license")).isEqualTo(result.provider().licenceMetadata());
      assertThat(row.get("schema_version")).isEqualTo("insurance-quote.v1");
      assertThat(row.get("result_count")).isEqualTo(4);
      assertThat(row.get("inventory_availability")).isEqualTo("AVAILABLE");
      assertThat(row.get("status")).isEqualTo("SUCCESS");
      assertThat(((Number) row.get("latency_millis")).longValue()).isNotNegative();
      assertThat((BigDecimal) row.get("cost_usd")).isZero();
      assertThat(row.get("retrieved_at")).isNotNull(); assertThat(row.get("finished_at")).isNotNull();
      assertThat(row.get("query_json")).isNull();
      assertThat(row.values()).noneMatch(value -> value != null
          && List.of("33602", "1HGCM82633A004352", "Accord", "Current Insurer").stream().anyMatch(value.toString()::contains));

      var provider = new InsuranceQuoteAdapter.Provider("LICENSED_TEST", "v1", "TEST_EXECUTED_TERMS", false);
      var limited = InsuranceQuoteContract.unavailable(provider, InsuranceQuoteAdapter.Reason.RATE_LIMITED,
          Instant.now(), new BigDecimal("0.125"));
      var metered = app.context().getBean(ProviderMeter.class).callInsuranceQuote(scope, provider, () -> limited);
      assertThat(metered.insurers()).isEmpty();
      var outage = jdbc.queryForMap("select * from provider_usage where case_id = ? and provider = 'LICENSED_TEST'", id);
      assertThat(outage.get("status")).isEqualTo("PROVIDER_UNAVAILABLE");
      assertThat(outage.get("result_count")).isEqualTo(0);
      assertThat(outage.get("inventory_availability")).isEqualTo("RATE_LIMITED");
      assertThat((BigDecimal) outage.get("cost_usd")).isEqualByComparingTo("0.125");

      var calls = new java.util.concurrent.atomic.AtomicBoolean();
      assertThatThrownBy(() -> app.context().getBean(ProviderMeter.class).callInsuranceQuote(
          InsuranceQuoteScope.forCase("missing-case"), provider, () -> { calls.set(true); return limited; }))
          .isInstanceOf(com.dealanalyzer.buyingcase.ResourceNotFoundException.class);
      assertThat(calls).isFalse();
    }
  }

  @Test void journeyScopedRequestPersistsWithoutACaseAndIsDeletedWithTheJourney() throws Exception {
    links.clear();
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("insurance-quote-journey", false,
        EmailConfiguration.class, "--document.storage.encryption-key=0123456789abcdef0123456789abcdef")) {
      var client = app.client();
      post(client, "/api/v1/account/sign-in-requests", Map.of("email", "insurance-buyer@example.com"));
      String session = AccountSessionTestSupport.verify(client, links.getLast().split("#sign-in=")[1]);
      String cookie = AccountSessionTestSupport.cookie(session);
      String account = app.context().getBean(AccountJourneyAccess.class).accountId(session);
      String journey = new ObjectMapper().readTree(client.post().uri("/api/v1/journeys")
          .header("Cookie", cookie).header("Account-Request", "1").header("Idempotency-Key", "insurance-journey")
          .contentType(MediaType.APPLICATION_JSON).body(BuyingJourneyAcceptanceTest.brief())
          .retrieve().body(String.class)).path("id").asText();
      var application = new InsuranceApplication(List.of(new Driver(41, 22, 0, 0)), new Garaging("33602", "FL"),
          new InsuredVehicle(null, 2023, "Honda", "Accord", "EX", 12000),
          CoverageSet.of(new Coverage(Coverage.Type.COLLISION, null, null, 50000L)), null);
      var consent = new InsuranceQuoteConsent("synthetic-partner-consent.v1", Instant.now());
      app.context().getBean(InsuranceQuoteService.class)
          .request(InsuranceQuoteScope.forJourney(account, journey), application, consent);
      var jdbc = app.context().getBean(JdbcTemplate.class);
      var row = jdbc.queryForMap("select * from provider_usage where journey_id = ? and operation = 'INSURANCE_QUOTE'", journey);
      assertThat(row.get("case_id")).isNull();
      assertThat(row.get("account_id")).isEqualTo(account);
      assertThat(row.get("status")).isEqualTo("SUCCESS");
      assertThat(row.values()).noneMatch(value -> value != null
          && List.of("33602", "Accord").stream().anyMatch(value.toString()::contains));
      var provider = app.context().getBean(InsuranceQuoteAdapter.class).provider();
      assertThatThrownBy(() -> app.context().getBean(ProviderUsageStore.class)
          .startInsuranceQuote(InsuranceQuoteScope.forJourney("other-account", journey), provider))
          .isInstanceOf(com.dealanalyzer.buyingcase.ResourceNotFoundException.class);
      client.delete().uri("/api/v1/journeys/" + journey).header("Cookie", cookie).header("Account-Request", "1")
          .retrieve().toBodilessEntity();
      assertThat(jdbc.queryForObject("select count(*) from provider_usage where journey_id = ?", Long.class, journey)).isZero();
    }
  }
}

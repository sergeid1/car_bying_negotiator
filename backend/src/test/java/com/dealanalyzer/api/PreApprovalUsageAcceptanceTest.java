package com.dealanalyzer.api;

import static org.assertj.core.api.Assertions.*;
import static com.dealanalyzer.api.HttpAcceptanceHarness.post;
import com.dealanalyzer.account.AccountJourneyAccess;
import com.dealanalyzer.buyingcase.ResourceNotFoundException;
import com.dealanalyzer.loan.preapproval.*;
import com.dealanalyzer.operations.ProviderUsageStore;
import com.dealanalyzer.recovery.RecoveryEmail;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

class PreApprovalUsageAcceptanceTest {
  @TempDir Path directory;
  static final CopyOnWriteArrayList<String> links = new CopyOnWriteArrayList<>();
  @TestConfiguration static class EmailConfiguration {
    @Bean @Primary RecoveryEmail loanEmail() { return (recipient, link) -> links.add(link); }
  }
  @Test void caseUsageContainsOnlyOperationalMetadata() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).start("loan-case", false,
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef")) {
      String caseId = post(app.client(), "/api/v1/cases", CasePortabilityAcceptanceTest.buyingCase()).at("/case/id").asText();
      app.context().getBean(PreApprovalService.class).request(caseId, null, null, application(), authorization());
      var jdbc = new JdbcTemplate(app.context().getBean(javax.sql.DataSource.class));
      var row = jdbc.queryForMap("select * from provider_usage where operation = 'PRE_APPROVAL' and case_id = ?", caseId);
      assertThat(row.get("ACCOUNT_ID")).isNull(); assertThat(row.get("JOURNEY_ID")).isNull();
      assertOperationalMetadata(row);
    }
  }
  private LoanApplication application() {
    return new LoanApplication(2345678, 48, new LoanApplication.Vehicle(2023, "Honda", "Civic", 12345),
        8765432, LoanApplication.Housing.RENT, 198765);
  }
  private CreditAuthorization authorization() {
    return new CreditAuthorization("private-consent-version", Instant.now().minusSeconds(60));
  }
  private void assertOperationalMetadata(Map<String, Object> row) {
    assertThat(row.get("PROVIDER")).isEqualTo("SYNTHETIC_PRE_APPROVAL");
    assertThat(row.get("SCHEMA_VERSION")).isEqualTo("pre-approval.v1");
    assertThat(row.get("QUERY_JSON")).isNull();
    assertThat(row.get("RESULT_COUNT")).isEqualTo(1);
    assertThat(row.get("STATUS")).isEqualTo("SUCCESS");
    assertThat(row.get("INVENTORY_AVAILABILITY")).isEqualTo("AVAILABLE");
    assertThat(row.get("RETRIEVED_AT")).isNotNull();
    assertThat(((Number) row.get("LATENCY_MILLIS")).longValue()).isNotNegative();
    assertThat((java.math.BigDecimal) row.get("COST_USD")).isZero();
    assertThat(row.toString()).doesNotContain("2345678", "8765432", "198765", "private-consent-version",
        "Honda", "lender.invalid", "synthetic-loan-1");
  }
  @Test void journeyUsageContainsNoApplicationFactsAndDeletionCannotRecreateIt() throws Exception {
    links.clear();
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("loan-usage", false,
        EmailConfiguration.class, "--document.storage.encryption-key=0123456789abcdef0123456789abcdef")) {
      var client = app.client();
      post(client, "/api/v1/account/sign-in-requests", Map.of("email", "loan-buyer@example.com"));
      String session = AccountSessionTestSupport.verify(client, links.getLast().split("#sign-in=")[1]);
      String cookie = AccountSessionTestSupport.cookie(session);
      String account = app.context().getBean(AccountJourneyAccess.class).accountId(session);
      String journey = new ObjectMapper().readTree(client.post().uri("/api/v1/journeys")
          .header("Cookie", cookie).header("Account-Request", "1").header("Idempotency-Key", "loan-journey")
          .contentType(MediaType.APPLICATION_JSON).body(BuyingJourneyAcceptanceTest.brief())
          .retrieve().body(String.class)).path("id").asText();
      var service = app.context().getBean(PreApprovalService.class);
      var response = service.request(null, account, journey, application(), authorization());
      var jdbc = new JdbcTemplate(app.context().getBean(javax.sql.DataSource.class));
      var rows = jdbc.queryForList("select * from provider_usage where operation = 'PRE_APPROVAL'");
      assertThat(rows).hasSize(1);
      rows.forEach(this::assertOperationalMetadata);
      var journeyRow = rows.stream().filter(row -> journey.equals(row.get("JOURNEY_ID"))).findFirst().orElseThrow();
      assertThat(journeyRow.get("CASE_ID")).isNull();
      assertThat(journeyRow.get("ACCOUNT_ID")).isEqualTo(account);
      var usage = app.context().getBean(ProviderUsageStore.class);
      var provider = app.context().getBean(PreApprovalAdapter.class).provider();
      assertThatThrownBy(() -> usage.startPreApproval(null, "other-account", journey, provider))
          .isInstanceOf(ResourceNotFoundException.class);
      client.delete().uri("/api/v1/journeys/" + journey).header("Cookie", cookie).header("Account-Request", "1")
          .retrieve().toBodilessEntity();
      assertThat(jdbc.queryForObject("select count(*) from provider_usage where journey_id = ?", Long.class, journey)).isZero();
      String deletedUsage = (String) journeyRow.get("ID");
      usage.preApprovalResult(deletedUsage, response); usage.preApprovalLatency(deletedUsage, 123);
      assertThat(jdbc.queryForObject("select count(*) from provider_usage where id = ?", Long.class, deletedUsage)).isZero();
      assertThatThrownBy(() -> usage.startPreApproval(null, account, journey, provider)).isInstanceOf(ResourceNotFoundException.class);
    }
  }
}

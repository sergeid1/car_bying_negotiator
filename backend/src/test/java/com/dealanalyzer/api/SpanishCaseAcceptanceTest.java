package com.dealanalyzer.api;

import static com.dealanalyzer.api.HttpAcceptanceHarness.*;
import static org.assertj.core.api.Assertions.*;
import com.dealanalyzer.notification.BuyerNotifications;
import com.dealanalyzer.notification.NotificationType;
import com.dealanalyzer.recovery.RecoveryEmail;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

class SpanishCaseAcceptanceTest {
  @TempDir Path directory;
  record Delivery(RecoveryEmail.Purpose purpose, String language, String link) {}
  static final CopyOnWriteArrayList<Delivery> deliveries = new CopyOnWriteArrayList<>();
  @TestConfiguration static class EmailConfiguration {
    @Bean @Primary RecoveryEmail spanishTestEmail() {
      return new RecoveryEmail() {
        public void send(String recipient, String link) { deliveries.add(new Delivery(Purpose.CASE_RECOVERY, "en", link)); }
        public void send(String recipient, String link, Purpose purpose) { deliveries.add(new Delivery(purpose, "en", link)); }
        public void send(String recipient, String link, Purpose purpose, String language) { deliveries.add(new Delivery(purpose, language, link)); }
      };
    }
  }
  @Test void spanishCaseUsesSpanishEmailsExplanationsAndPdfWithoutRewritingTheReport() throws Exception {
    deliveries.clear();
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("spanish-case", false, EmailConfiguration.class,
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef")) {
      var client = app.client();
      var input = new HashMap<>(CasePortabilityAcceptanceTest.buyingCase()); input.put("language", "es");
      var created = post(client, "/api/v1/cases", input);
      String caseId = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      String base = "/api/v1/cases/" + caseId;
      post(client, base + "/recovery-email", Map.of("email", "spanish@example.com", "language", "en"), "recovery", token);
      assertThat(deliveries).singleElement().satisfies(delivery -> {
        assertThat(delivery.language()).isEqualTo("es"); assertThat(delivery.purpose()).isEqualTo(RecoveryEmail.Purpose.CASE_RECOVERY);
      });
      client.post().uri("/api/v1/account/sign-in-requests").header("Account-Request", "1").header("Idempotency-Key", "spanish-sign-in")
          .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(Map.of("email", "spanish@example.com", "language", "es"))
          .retrieve().toBodilessEntity();
      assertThat(deliveries.getLast().language()).isEqualTo("es");
      assertThat(deliveries.getLast().purpose()).isEqualTo(RecoveryEmail.Purpose.ACCOUNT_SIGN_IN);
      client.post().uri("/api/v1/account/sign-in/verify").header("Account-Request", "1")
          .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
          .body(Map.of("token", deliveries.getLast().link().split("#sign-in=", 2)[1])).retrieve().toBodilessEntity();
      var jdbc = app.context().getBean(JdbcTemplate.class);
      String account = jdbc.queryForObject("select id from buyer_accounts", String.class);
      var notifications = app.context().getBean(BuyerNotifications.class);
      for (var type : NotificationType.values()) notifications.notifyCase(account, type, "spanish-" + type, caseId);
      assertThat(deliveries).extracting(Delivery::purpose).contains(RecoveryEmail.Purpose.values());
      assertThat(deliveries).allSatisfy(delivery -> assertThat(delivery.language()).isEqualTo("es"));

      post(client, base + "/analyses", Map.of(), "spanish-report", token);
      var report = get(client, base + "/reports/1", token);
      assertThat(report.path("schemaVersion").asText()).isEqualTo("core-deal-analysis-report.v2");
      assertThat(report.path("language").asText()).isEqualTo("es");
      assertThat(report.path("textFindings").size()).isGreaterThan(5);
      var spanish = get(client, base + "/reports/1/explanation?language=es", token);
      var english = get(client, base + "/reports/1/explanation", token);
      assertThat(spanish.at("/statements/0/text").asText()).startsWith("Este informe no puede verificar");
      assertThat(english.at("/statements/0/text").asText()).startsWith("This report cannot verify");
      assertThat(get(client, base + "/reports/1/explanation?language=es", token)).isEqualTo(spanish);
      assertThat(jdbc.queryForObject("select count(*) from spanish_report_explanations where case_id = ?", Integer.class, caseId)).isEqualTo(1);
      var job = post(client, base + "/exports", Map.of("format", "PDF", "reportVersion", 1), "spanish-pdf", token);
      byte[] bytes = client.get().uri(base + "/exports/" + job.path("id").asText()).header("Case-Access-Token", token)
          .retrieve().body(byte[].class);
      try (var document = Loader.loadPDF(bytes)) {
        assertThat(new PDFTextStripper().getText(document)).contains("Informe de análisis", "La información faltante", "Limitaciones");
      }
      assertThat(get(client, base + "/reports/1", token)).isEqualTo(report);
    }
  }
}

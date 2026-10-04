package com.dealanalyzer.api;

import static com.dealanalyzer.api.HttpAcceptanceHarness.*;
import static com.dealanalyzer.api.ServiceOperationsAcceptanceTest.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.HttpClientErrorException;

class ContributionDecisionAcceptanceTest {
  @TempDir Path directory;
  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  private static final String BASE = "/api/v1/operator/contribution/decisions";
  private static final String WINDOW = "?from=2020-01-01&to=2100-01-01";
  private Map<String, Object> freeze() {
    return Map.of("from", "2020-01-01", "to", "2100-01-01", "thresholds", List.of(
        threshold("CONTINUE", "PAID_PURCHASE_RATE", "AT_LEAST", "0.5", 1),
        threshold("REPRICE", "MEDIAN_CONTRIBUTION_USD", "AT_MOST", "10", 1),
        threshold("STOP", "REFUND_RATE", "AT_LEAST", "0.5", 1)));
  }
  private Map<String, Object> threshold(String decision, String metric, String bound, String value, int minimum) {
    return Map.of("decision", decision, "metric", metric, "bound", bound, "value", value, "minimumDenominator", minimum);
  }
  private Map<String, Object> decision() {
    return Map.of("thresholdVersion", 2, "decision", "REPRICE", "rationale", "INSUFFICIENT_EVIDENCE");
  }
  private JsonNode get(RunningApplication app, String path, String credential) throws Exception {
    return JSON.readTree(app.client().get().uri(path).header("Authorization", "Bearer " + credential).retrieve().body(String.class));
  }
  @Test void thresholdsFreezeBeforeReviewAndDecisionKeepsOriginalAggregateEvidence() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).start("decisions", false, SETTINGS)) {
      var created = post(app.client(), "/api/v1/cases", PilotFeedbackAcceptanceTest.buyingCase());
      String id = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      assertThatThrownBy(() -> opPost(app.client(), BASE + "/thresholds", freeze(), token)).isInstanceOf(HttpClientErrorException.Unauthorized.class);
      assertThatThrownBy(() -> opPost(app.client(), BASE + "/thresholds", freeze(), REVIEWER)).isInstanceOf(HttpClientErrorException.Forbidden.class);
      assertThat(opPost(app.client(), BASE + "/thresholds", freeze(), SUPPORT).path("version").asInt()).isEqualTo(1);
      assertThat(get(app, BASE + WINDOW, SUPPORT).path("reviews")).isEmpty();
      assertThat(opPost(app.client(), BASE + "/thresholds", freeze(), SUPPORT).path("version").asInt()).isEqualTo(2);
      assertThatThrownBy(() -> opPost(app.client(), BASE + WINDOW, decision(), SUPPORT)).isInstanceOf(HttpClientErrorException.Conflict.class);
      var jdbc = new JdbcTemplate(app.context().getBean(javax.sql.DataSource.class));
      // Stated willingness and Buyer-entered terms are not purchases or verified offers.
      jdbc.update("insert into experiment_assignments values (?, ?, 'A', 2000, true, 'test', current_timestamp)", UUID.randomUUID().toString(), id);
      jdbc.update("insert into outcome_feedback (id, case_id, stage, willing_to_pay, payload_json, created_at) values (?, ?, 'FINAL', true, '{\"evidence\":\"BUYER_REPORTED_UNVERIFIED\",\"sellingPriceMinor\":123456}', current_timestamp)", UUID.randomUUID().toString(), id);
      var live = get(app, "/api/v1/operator/contribution/metrics" + WINDOW, SUPPORT);
      assertThat(live.at("/paidPurchases/numerator").asInt()).isZero();
      assertThat(live.at("/casesWithVerifiedOffer/numerator").asInt()).isZero();
      assertThatThrownBy(() -> opPost(app.client(), BASE + "/thresholds", freeze(), SUPPORT)).isInstanceOf(HttpClientErrorException.Conflict.class);
      var overlap = new HashMap<>(freeze()); overlap.put("to", "2101-01-01");
      assertThatThrownBy(() -> opPost(app.client(), BASE + "/thresholds", overlap, SUPPORT)).isInstanceOf(HttpClientErrorException.Conflict.class);
      assertThatThrownBy(() -> opPost(app.client(), BASE + "/review" + WINDOW, Map.of(), REVIEWER)).isInstanceOf(HttpClientErrorException.Forbidden.class);
      var review = opPost(app.client(), BASE + "/review" + WINDOW, Map.of(), SUPPORT);
      assertThat(review.path("payload")).isEqualTo(live);
      var saved = opPost(app.client(), BASE + WINDOW, decision(), SUPPORT);
      assertThat(saved.at("/payload/snapshot")).isEqualTo(live);
      assertThat(saved.at("/payload/evaluations/0/status").asText()).isEqualTo("NOT_MET");
      assertThat(saved.at("/payload/evaluations/1/status").asText()).isEqualTo("NOT_MEASURABLE");
      assertThat(saved.at("/payload/evaluations/2/status").asText()).isEqualTo("NOT_MEASURABLE");
      assertThat(saved.toString()).doesNotContain(id, token, "123456", "sellingPrice", "willingToPay", "caseId", "documentId", "VIN");
      jdbc.update("insert into case_purchases (id, case_id, request_key, offer_id, amount_minor, currency, status, checkout, created_at) values (?, ?, 'test', 'test', 2000, 'USD', 'PAID', 'RECORDED_TEST_PAYMENT', current_timestamp)", id, id);
      assertThat(get(app, "/api/v1/operator/contribution/metrics" + WINDOW, SUPPORT).at("/paidPurchases/numerator").asInt()).isEqualTo(1);
      assertThat(opPost(app.client(), BASE + "/review" + WINDOW, Map.of(), SUPPORT)).isEqualTo(review);
      assertThat(opPost(app.client(), BASE + WINDOW, decision(), SUPPORT)).isEqualTo(saved);
      var changed = new HashMap<>(decision()); changed.put("decision", "STOP");
      assertThatThrownBy(() -> opPost(app.client(), BASE + WINDOW, changed, SUPPORT)).isInstanceOf(HttpClientErrorException.Conflict.class);
      assertThatThrownBy(() -> get(app, BASE + WINDOW, REVIEWER)).isInstanceOf(HttpClientErrorException.Forbidden.class);
      assertThatThrownBy(() -> opPost(app.client(), BASE + WINDOW, decision(), REVIEWER)).isInstanceOf(HttpClientErrorException.Forbidden.class);
      assertThat(app.client().get().uri(BASE + WINDOW).header("Authorization", "Bearer " + SUPPORT).retrieve().toEntity(String.class).getHeaders().getCacheControl()).isEqualTo("no-store");
      // Hibernate cannot dirty-update the frozen definition.
      var transaction = new org.springframework.transaction.support.TransactionTemplate(app.context().getBean(org.springframework.transaction.PlatformTransactionManager.class));
      transaction.executeWithoutResult(status -> {
        var manager = app.context().getBean(jakarta.persistence.EntityManager.class);
        Object record = manager.createQuery("from ContributionDecisionRecord where kind = 'FREEZE' order by version").getResultList().getFirst();
        try { var field = record.getClass().getDeclaredField("payload"); field.setAccessible(true); field.set(record, "{}"); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        manager.flush();
      });
      assertThat(get(app, BASE + WINDOW, SUPPORT).at("/thresholds/0/payload/thresholds")).hasSize(3);
      post(app.client(), "/api/v1/cases/" + id + "/deletion", Map.of(), "decision-delete", token);
      app.context().getBean(com.dealanalyzer.buyingcase.persistence.CaseDeletionWorker.class).runOnce();
      assertThat(jdbc.queryForObject("select count(*) from analysis_cases where id = ?", Long.class, id)).isZero();
      assertThat(opPost(app.client(), BASE + WINDOW, decision(), SUPPORT)).isEqualTo(saved);
    }
  }
  @Test void readingAnUnfrozenWindowPermanentlyPreventsLateFreezeAndInvalidRequestsAreRejected() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).start("late-decisions", false, SETTINGS)) {
      var invalid = new HashMap<>(freeze()); invalid.put("to", "2020-01-01");
      assertThatThrownBy(() -> opPost(app.client(), BASE + "/thresholds", invalid, SUPPORT)).isInstanceOf(HttpClientErrorException.BadRequest.class);
      invalid.put("to", "2100-01-01"); invalid.put("thresholds", List.of(threshold("CONTINUE", "PAID_PURCHASE_RATE", "AT_LEAST", "2", 0)));
      assertThatThrownBy(() -> opPost(app.client(), BASE + "/thresholds", invalid, SUPPORT)).isInstanceOf(HttpClientErrorException.BadRequest.class);
      opPost(app.client(), BASE + "/review" + WINDOW, Map.of(), SUPPORT);
      assertThatThrownBy(() -> opPost(app.client(), BASE + "/thresholds", freeze(), SUPPORT)).isInstanceOf(HttpClientErrorException.Conflict.class);
      assertThatThrownBy(() -> opPost(app.client(), BASE + WINDOW, decision(), SUPPORT)).isInstanceOf(HttpClientErrorException.Conflict.class);
    }
  }
}

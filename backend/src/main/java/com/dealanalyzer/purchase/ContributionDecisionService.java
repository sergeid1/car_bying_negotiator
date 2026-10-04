package com.dealanalyzer.purchase;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(isolation = Isolation.REPEATABLE_READ)
class ContributionDecisionService {
  enum Decision { CONTINUE, REPRICE, STOP }
  enum Metric { PAID_PURCHASE_RATE, REFUND_RATE, COMPLETED_REPORT_RATE, MEDIAN_CONTRIBUTION_USD }
  enum Bound { AT_LEAST, AT_MOST }
  enum Rationale { ECONOMICS_ACCEPTABLE, PRICE_OR_COST_CHANGE_NEEDED, ECONOMICS_UNACCEPTABLE, INSUFFICIENT_EVIDENCE }
  record Threshold(@NotNull Decision decision, @NotNull Metric metric, @NotNull Bound bound,
      @NotNull BigDecimal value, @Min(1) long minimumDenominator) {}
  record Freeze(@NotNull LocalDate from, @NotNull LocalDate to,
      @NotEmpty @Size(max = 40) List<@Valid Threshold> thresholds) {}
  record RecordDecision(@Min(1) int thresholdVersion, @NotNull Decision decision, @NotNull Rationale rationale) {}
  private final ContributionReviewStore store;
  private final ContributionService contributions;
  ContributionDecisionService(ContributionReviewStore store, ContributionService contributions) {
    this.store = store; this.contributions = contributions;
  }
  Object freeze(Freeze request) {
    window(request.from(), request.to()); store.lock();
    if (store.reviewedOverlap(request.from(), request.to())) throw new ResponseStatusException(HttpStatus.CONFLICT);
    var actions = EnumSet.noneOf(Decision.class);
    var keys = new HashSet<String>();
    for (var threshold : request.thresholds()) {
      actions.add(threshold.decision());
      if (!keys.add(threshold.decision() + ":" + threshold.metric())) badRequest();
      if (threshold.metric() != Metric.MEDIAN_CONTRIBUTION_USD
          && (threshold.value().signum() < 0 || threshold.value().compareTo(BigDecimal.ONE) > 0)) badRequest();
    }
    if (actions.size() != Decision.values().length) badRequest();
    int version = store.records(request.from(), request.to(), "FREEZE").size() + 1;
    return store.view(store.append(request.from(), request.to(), "FREEZE", version, request));
  }
  Object history(LocalDate from, LocalDate to) {
    window(from, to); store.lock();
    return Map.of("thresholds", store.records(from, to, "FREEZE").stream().map(store::view).toList(),
        "reviews", store.records(from, to, "REVIEW").stream().map(store::view).toList(),
        "decisions", store.records(from, to, "DECISION").stream().map(store::view).toList());
  }
  Object review(LocalDate from, LocalDate to) {
    window(from, to); store.lock();
    if (store.records(from, to, "REVIEW").isEmpty()) contributions.snapshot(from, to);
    return store.view(store.records(from, to, "REVIEW").getFirst());
  }
  Object decide(LocalDate from, LocalDate to, RecordDecision request) {
    window(from, to); store.lock();
    var frozen = store.records(from, to, "FREEZE");
    if (request.thresholdVersion() > frozen.size()) throw new ResponseStatusException(HttpStatus.CONFLICT);
    var reviews = store.records(from, to, "REVIEW");
    if (reviews.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT);
    var decisions = store.records(from, to, "DECISION");
    if (!decisions.isEmpty()) {
      JsonNode saved = store.payload(decisions.getFirst());
      if (saved.path("thresholdVersion").asInt() != request.thresholdVersion()
          || !saved.path("decision").asText().equals(request.decision().name())
          || !saved.path("rationale").asText().equals(request.rationale().name()))
        throw new ResponseStatusException(HttpStatus.CONFLICT);
      return store.view(decisions.getFirst());
    }
    var definition = frozen.get(request.thresholdVersion() - 1);
    JsonNode snapshot = store.payload(reviews.getFirst());
    var evaluations = new ArrayList<Object>();
    for (JsonNode threshold : store.payload(definition).path("thresholds")) evaluations.add(evaluate(threshold, snapshot));
    return store.view(store.append(from, to, "DECISION", 0, Map.of(
        "schemaVersion", "contribution-decision.v1", "thresholdVersion", request.thresholdVersion(),
        "thresholdsFrozenAt", definition.createdAt, "outcomeReviewOpenedAt", reviews.getFirst().createdAt,
        "decision", request.decision(), "rationale", request.rationale(), "snapshot", snapshot,
        "evaluations", evaluations, "evidenceScope", "Recorded purchases and verified offer presence only; Buyer-reported outcomes and willingness-to-pay surveys are excluded. No verified outcome improvement is inferred.")));
  }
  static Object evaluate(JsonNode threshold, JsonNode snapshot) {
    Metric metric = Metric.valueOf(threshold.path("metric").asText());
    String field = switch (metric) {
      case PAID_PURCHASE_RATE -> "paidPurchases";
      case REFUND_RATE -> "refunds";
      case COMPLETED_REPORT_RATE -> "completedReports";
      case MEDIAN_CONTRIBUTION_USD -> "contributionMargin";
    };
    JsonNode measured = snapshot.path(field);
    boolean margin = metric == Metric.MEDIAN_CONTRIBUTION_USD;
    long denominator = measured.path(margin ? "paidCaseCount" : "denominator").asLong();
    JsonNode value = measured.path(margin ? "medianUsd" : "rate");
    boolean measurable = value.isNumber() && denominator >= threshold.path("minimumDenominator").asLong()
        && (!margin || measured.path("status").asText().equals("MEASURED"))
        && snapshot.at("/historicalDeletionCoverage/status").asText().equals("COMPLETE");
    String result = "NOT_MEASURABLE";
    if (measurable) {
      int compared = value.decimalValue().compareTo(threshold.path("value").decimalValue());
      boolean met = threshold.path("bound").asText().equals("AT_LEAST") ? compared >= 0 : compared <= 0;
      result = met ? "MET" : "NOT_MET";
    }
    var evaluation = new LinkedHashMap<String, Object>();
    evaluation.put("threshold", threshold); evaluation.put("measurement", measured);
    evaluation.put("denominator", denominator); evaluation.put("status", result);
    return evaluation;
  }
  private static void window(LocalDate from, LocalDate to) { if (!from.isBefore(to)) badRequest(); }
  private static void badRequest() { throw new ResponseStatusException(HttpStatus.BAD_REQUEST); }
}

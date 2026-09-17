package com.dealanalyzer.pilot;

import com.dealanalyzer.time.PersistedInstant;

import com.dealanalyzer.buyingcase.EphemeralBuyingCaseService;
import com.dealanalyzer.buyingcase.IdempotencyConflictException;
import com.dealanalyzer.buyingcase.ResourceNotFoundException;
import com.dealanalyzer.buyingcase.persistence.NegotiationService;
import jakarta.persistence.EntityManager;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class FeedbackService {
  private final ExperimentAssignmentRepository assignments;
  private final OutcomeFeedbackRepository outcomes;
  private final com.fasterxml.jackson.databind.ObjectMapper json;
  private final PilotEvents events;
  private final FindingFeedbackRepository findings;
  private final EphemeralBuyingCaseService access;
  private final NegotiationService negotiation;
  private final EntityManager manager;
  public FeedbackService(FindingFeedbackRepository findings, EphemeralBuyingCaseService access,
      NegotiationService negotiation, EntityManager manager, OutcomeFeedbackRepository outcomes,
      com.fasterxml.jackson.databind.ObjectMapper json, PilotEvents events, ExperimentAssignmentRepository assignments) {
    this.assignments = assignments;
    this.outcomes = outcomes; this.json = json; this.events = events;
    this.findings = findings; this.access = access; this.negotiation = negotiation; this.manager = manager;
  }
  public enum Answer { YES, NO, UNSURE }
  public record FindingRequest(@Min(1) int reportVersion, @NotBlank @Size(max = 160) String findingPointer,
      @NotNull Answer accurate, @NotNull Answer understandable, @NotNull Answer useful) {}
  public record Finding(String id, int reportVersion, String findingPointer, String accurate,
      String understandable, String useful, Instant createdAt) {}

  @Transactional
  public Finding rate(String caseId, String token, FindingRequest input) {
    lock(caseId, token);
    var language = negotiation.get(caseId, input.reportVersion(), token).at("/language/items");
    boolean found = false;
    for (var item : language) if (input.findingPointer().equals(item.path("sourcePointer").asText())) found = true;
    if (!found) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    var previous = findings.findByCaseIdAndReportVersionAndFindingPointer(caseId, input.reportVersion(), input.findingPointer());
    if (previous.isPresent()) {
      var row = previous.get();
      if (!row.accurate.equals(input.accurate().name()) || !row.understandable.equals(input.understandable().name())
          || !row.useful.equals(input.useful().name())) throw new IdempotencyConflictException();
      return view(row);
    }
    var row = new FindingFeedbackEntity(); row.id = UUID.randomUUID().toString(); row.caseId = caseId;
    row.reportVersion = input.reportVersion(); row.findingPointer = input.findingPointer();
    row.accurate = input.accurate().name(); row.understandable = input.understandable().name(); row.useful = input.useful().name();
    row.createdAt = Instant.now();
    events.record(caseId, PilotEvents.Type.FINDING_FEEDBACK, PilotEvents.Category.RECEIVED, null);
    return view(findings.saveAndFlush(row));
  }
  @Transactional(readOnly = true)
  public Map<String, Object> history(String caseId, String token) {
    access.reopen(caseId, token);
    var result = new LinkedHashMap<String, Object>();
    result.putAll(Map.of("findings", findings.findByCaseIdOrderByCreatedAt(caseId).stream().map(this::view).toList(), "outcomes", outcomes.findByCaseIdOrderByCreatedAt(caseId).stream().map(this::outcomeView).toList()));
    assignments.findByCaseId(caseId).ifPresent(a -> result.put("experiment", Map.of("variant", a.variant, "priceMinor", a.priceMinor, "currency", "USD")));
    return result;
  }
  public enum Stage { REVISED, FINAL }
  public enum Action { NEGOTIATED, REQUESTED_REVISION, WALKED_AWAY, PURCHASED, NO_ACTION }
  public enum Disappointment { VERY_DISAPPOINTED, SOMEWHAT_DISAPPOINTED, NOT_DISAPPOINTED }
  public record OutcomeRequest(@NotNull Stage stage, Action action,
      @Min(0) @Max(100000000) Long sellingPriceMinor, @Min(0) @Max(100000000) Long outTheDoorMinor,
      @jakarta.validation.constraints.Pattern(regexp = "USD") String currency,
      @DecimalMin("0") @DecimalMax("100") java.math.BigDecimal aprPercent,
      @Min(1) @Max(120) Integer termMonths, Boolean understoodDecision, Answer useful, Answer trust,
      Boolean liveUse, Disappointment disappointment, Boolean willingToPay) {}

  @Transactional
  public Object outcome(String caseId, String token, OutcomeRequest input) {
    lock(caseId, token);
    if ((input.sellingPriceMinor() != null || input.outTheDoorMinor() != null) && input.currency() == null
        || input.disappointment() != null && input.liveUse() == null)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    if (input.willingToPay() != null && assignments.findByCaseId(caseId).isEmpty())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    if (input.action() == null && input.sellingPriceMinor() == null && input.outTheDoorMinor() == null
        && input.aprPercent() == null && input.termMonths() == null && input.understoodDecision() == null
        && input.useful() == null && input.trust() == null && input.liveUse() == null && input.disappointment() == null
        && input.willingToPay() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    String payload = json.valueToTree(input).toString();
    var previous = outcomes.findByCaseIdAndStage(caseId, input.stage().name());
    if (previous.isPresent()) {
      if (!previous.get().payloadJson.equals(payload)) throw new IdempotencyConflictException();
      return outcomeView(previous.get());
    }
    var row = new OutcomeFeedbackEntity(); row.id = UUID.randomUUID().toString(); row.caseId = caseId;
    row.stage = input.stage().name(); row.action = name(input.action()); row.useful = name(input.useful());
    row.trust = name(input.trust()); row.liveUse = input.liveUse(); row.understoodDecision = input.understoodDecision();
    row.disappointment = name(input.disappointment()); row.willingToPay = input.willingToPay();
    row.payloadJson = payload; row.createdAt = Instant.now();
    events.record(caseId, PilotEvents.Type.OUTCOME_FEEDBACK, PilotEvents.Category.RECEIVED, null);
    return outcomeView(outcomes.saveAndFlush(row));
  }
  private static String name(Enum<?> value) { return value == null ? null : value.name(); }
  private com.fasterxml.jackson.databind.JsonNode outcomeView(OutcomeFeedbackEntity row) {
    try {
      var result = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(row.payloadJson);
      result.put("id", row.id); result.put("createdAt", PersistedInstant.format(row.createdAt));
      result.put("verification", "BUYER_REPORTED_UNVERIFIED"); return result;
    } catch (java.io.IOException error) { throw new IllegalStateException("Stored outcome unavailable"); }
  }
  void lock(String caseId, String token) {
    int count = manager.createQuery("update AnalysisCaseEntity c set c.lockVersion = c.lockVersion + 1 where c.id = :id and c.lifecycle = 'ACTIVE' and c.accessExpiresAt > :now")
        .setParameter("id", caseId).setParameter("now", Instant.now()).executeUpdate();
    if (count != 1) throw new ResourceNotFoundException("Buying Case not found");
    access.reopen(caseId, token);
  }
  private Finding view(FindingFeedbackEntity row) {
    return new Finding(row.id, row.reportVersion, row.findingPointer, row.accurate, row.understandable, row.useful, row.createdAt);
  }
}

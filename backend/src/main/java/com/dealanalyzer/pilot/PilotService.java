package com.dealanalyzer.pilot;

import com.dealanalyzer.buyingcase.ResourceNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Commercial experiments have no reference into the assessment or recommendation interfaces. */
@Service
public class PilotService {
  private final PilotProtocolRepository protocols;
  private final ExperimentAssignmentRepository assignments;
  private final PilotObservationRepository observations;
  private final EntityManager manager;
  private final ObjectMapper json;
  private final java.security.SecureRandom random = new java.security.SecureRandom();
  public PilotService(PilotProtocolRepository protocols, ExperimentAssignmentRepository assignments,
      PilotObservationRepository observations, EntityManager manager, ObjectMapper json) {
    this.protocols = protocols; this.assignments = assignments; this.observations = observations;
    this.manager = manager; this.json = json;
  }
  public enum Variant { A, B, C }
  public record Protocol(@NotNull @DecimalMin("0") @DecimalMax("1") Double completionMinimum,
      @NotNull @DecimalMin("0") @DecimalMax("1") Double usefulnessMinimum,
      @NotNull @DecimalMin("0") @DecimalMax("1") Double veryDisappointedMinimum,
      @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1") Double numericErrorMaximum,
      @NotNull @Min(0) @Max(0) Integer criticalPrivacyMaximum,
      @NotNull @Size(min = 2, max = 3) Map<@NotNull Variant, @NotNull @Min(0) @Max(100000) Long> variants) {}
  public record Enrollment(@AssertTrue boolean qualified) {}
  public record Assignment(String variant, long priceMinor, String currency, Instant enrolledAt) {}
  public enum Metric {
    FIELD_PRECISION, FIELD_RECALL, NUMERIC_EXACT_MATCH, MATERIAL_NUMERIC_ERROR,
    SEEDED_DISCREPANCY_FALSE_POSITIVE, SEEDED_DISCREPANCY_FALSE_NEGATIVE, COMPARABLE_RELEVANCE,
    VERIFIED_OUTCOME_IMPROVEMENT, PAID_CONVERSION, REFUND, COMPLAINT, SUPPORT_CONTACT,
    CRITICAL_PRIVACY_INCIDENT, LICENSING_INCIDENT
  }
  public enum Source { LABELED_EVALUATION, VERIFIED_PILOT_RECORDS }
  public record Observation(@NotNull UUID evidenceId, @NotNull Metric metric,
      @Min(0) long numerator, @Min(1) long denominator, @NotNull Source source,
      @Min(-1000000000000L) @Max(1000000000000L) Long totalDeltaMinor) {}

  @Transactional
  public Object freeze(Protocol protocol, String actor) {
    var row = protocols.lock();
    if (row.frozenAt != null || row.reviewedAt != null) throw conflict();
    row.protocolJson = json.valueToTree(protocol).toString(); row.frozenAt = Instant.now(); row.frozenBy = actor;
    return protocolView(row);
  }
  @Transactional(readOnly = true)
  public Object protocol() { return protocolView(requireFrozen(protocols.findById(1).orElseThrow())); }
  @Transactional
  public void markReviewed() {
    var row = requireFrozen(protocols.lock());
    if (row.reviewedAt == null) row.reviewedAt = Instant.now();
  }
  @Transactional
  public Assignment enroll(String caseId, String actor) {
    var protocol = requireFrozen(protocols.lock());
    int locked = manager.createQuery("update AnalysisCaseEntity c set c.lockVersion = c.lockVersion + 1 where c.id = :id and c.lifecycle = 'ACTIVE' and c.accessExpiresAt > :now")
        .setParameter("id", caseId).setParameter("now", Instant.now()).executeUpdate();
    if (locked != 1) throw new ResourceNotFoundException("Buying Case not found");
    var previous = assignments.findByCaseId(caseId);
    if (previous.isPresent()) return assignmentView(previous.get());
    // Enrollment must precede observed outcomes or analysis; it cannot cherry-pick completed cases.
    long reports = manager.createQuery("select count(r) from AnalysisReportEntity r where r.id.caseId = :id", Long.class).setParameter("id", caseId).getSingleResult();
    long outcomes = manager.createQuery("select count(o) from OutcomeFeedbackEntity o where o.caseId = :id", Long.class).setParameter("id", caseId).getSingleResult();
    if (reports != 0 || outcomes != 0) throw conflict();
    var variants = read(protocol.protocolJson).path("variants");
    var keys = new ArrayList<String>(); variants.fieldNames().forEachRemaining(keys::add); Collections.sort(keys);
    var row = new ExperimentAssignmentEntity(); row.id = UUID.randomUUID().toString(); row.caseId = caseId;
    row.variant = keys.get(random.nextInt(keys.size())); row.priceMinor = variants.path(row.variant).asLong();
    row.qualified = true; row.enrolledBy = actor; row.createdAt = Instant.now();
    assignments.saveAndFlush(row);
    manager.createNativeQuery("update pilot_cohort_totals set enrolled_count = enrolled_count + 1 where id = 1").executeUpdate();
    return assignmentView(row);
  }
  @Transactional
  public Object observe(Observation input, String actor) {
    requireFrozen(protocols.lock());
    if (input.totalDeltaMinor() != null && input.metric() != Metric.VERIFIED_OUTCOME_IMPROVEMENT) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    if (input.numerator() > input.denominator()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    boolean evaluation = EnumSet.of(Metric.FIELD_PRECISION, Metric.FIELD_RECALL, Metric.NUMERIC_EXACT_MATCH,
        Metric.MATERIAL_NUMERIC_ERROR, Metric.SEEDED_DISCREPANCY_FALSE_POSITIVE,
        Metric.SEEDED_DISCREPANCY_FALSE_NEGATIVE, Metric.COMPARABLE_RELEVANCE).contains(input.metric());
    if (evaluation != (input.source() == Source.LABELED_EVALUATION)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    var existing = observations.findById(input.evidenceId().toString());
    if (existing.isPresent()) {
      var row = existing.get();
      if (!row.metric.equals(input.metric().name()) || row.numerator != input.numerator() || row.denominator != input.denominator()
          || !row.source.equals(input.source().name()) || !Objects.equals(row.totalDeltaMinor, input.totalDeltaMinor())) throw conflict();
      return Map.of("evidenceId", row.id);
    }
    var row = new PilotObservationEntity(); row.id = input.evidenceId().toString(); row.metric = input.metric().name();
    row.numerator = input.numerator(); row.denominator = input.denominator(); row.source = input.source().name();
    row.recordedBy = actor; row.createdAt = Instant.now(); row.totalDeltaMinor = input.totalDeltaMinor(); observations.saveAndFlush(row);
    return Map.of("evidenceId", row.id);
  }
  private Assignment assignmentView(ExperimentAssignmentEntity row) { return new Assignment(row.variant, row.priceMinor, "USD", row.createdAt); }
  private Object protocolView(PilotProtocolEntity row) {
    var value = (com.fasterxml.jackson.databind.node.ObjectNode) read(row.protocolJson);
    value.put("schemaVersion", "pilot-protocol.v1"); value.put("frozenAt", row.frozenAt.toString());
    value.put("frozenBy", row.frozenBy); if (row.reviewedAt != null) value.put("reviewedAt", row.reviewedAt.toString());
    return value;
  }
  private com.fasterxml.jackson.databind.JsonNode read(String value) {
    try { return json.readTree(value); } catch (java.io.IOException error) { throw new IllegalStateException("Protocol unavailable"); }
  }
  private PilotProtocolEntity requireFrozen(PilotProtocolEntity row) { if (row.frozenAt == null) throw conflict(); return row; }
  private ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT); }
}

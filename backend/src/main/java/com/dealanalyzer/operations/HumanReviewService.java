package com.dealanalyzer.operations;

import com.dealanalyzer.buyingcase.EphemeralBuyingCaseService;
import com.dealanalyzer.buyingcase.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HumanReviewService {
  // Consent versions prevent a revoked request from becoming usable after a later grant.
  private static final String ELIGIBLE = """
      exists (select c.id from AnalysisCaseEntity c where c.id = r.caseId and c.lifecycle = 'ACTIVE' and c.accessExpiresAt > :now)
      and exists (select h.id from ConsentRecordEntity h where h.caseId = r.caseId and h.purpose = 'HUMAN_REVIEW'
        and h.action = 'GRANTED' and h.consentVersion = r.consentVersion
        and h.consentVersion = (select max(h2.consentVersion) from ConsentRecordEntity h2 where h2.caseId = r.caseId and h2.purpose = 'HUMAN_REVIEW'))
      and exists (select s.id from ConsentRecordEntity s where s.caseId = r.caseId and s.purpose = 'SERVICE_PROCESSING'
        and s.action = 'GRANTED' and s.consentVersion = (select max(s2.consentVersion) from ConsentRecordEntity s2 where s2.caseId = r.caseId and s2.purpose = 'SERVICE_PROCESSING'))
      and exists (select e.id from DocumentExtractionEntity e, SourceDocumentEntity d where e.id = r.extractionId
        and e.documentId = d.id and d.lifecycle = 'ACTIVE' and d.retentionDeadline > :now)
      """;
  private static final String METADATA = "select r.id as id, r.caseId as caseId, r.extractionId as extractionId, r.candidateId as candidateId, r.consentVersion as consentVersion, r.state as state, r.reviewer as reviewer, r.expiresAt as expiresAt, r.outcome as outcome, r.createdAt as createdAt from HumanReviewEntity r ";
  private final EntityManager manager;
  private final OperationsProjection projections;
  private final EphemeralBuyingCaseService cases;
  private final ReviewConsentLifecycle audit;
  private final Duration duration;
  public HumanReviewService(EntityManager manager, OperationsProjection projections, EphemeralBuyingCaseService cases,
      ReviewConsentLifecycle audit, @Value("${operations.review-duration:PT15M}") Duration duration) {
    if (duration.isNegative() || duration.isZero() || duration.compareTo(Duration.ofMinutes(15)) > 0)
      throw new IllegalArgumentException("Review duration must be positive and at most 15 minutes");
    this.manager = manager; this.projections = projections; this.cases = cases; this.audit = audit; this.duration = duration;
  }
  @Transactional
  public Map<String, Object> request(String caseId, String token, String extractionId, String candidateId) {
    lockCase(caseId); cases.reopen(caseId, token);
    var versions = manager.createQuery("select h.consentVersion from ConsentRecordEntity h where h.caseId = :id and h.purpose = 'HUMAN_REVIEW' and h.action = 'GRANTED' and h.consentVersion = (select max(h2.consentVersion) from ConsentRecordEntity h2 where h2.caseId = :id and h2.purpose = 'HUMAN_REVIEW')", Integer.class)
        .setParameter("id", caseId).getResultList();
    var candidates = manager.createQuery("select t.id from CandidateDealTermEntity t, DocumentExtractionEntity e where t.id = :candidate and t.extractionId = e.id and e.id = :extraction and e.caseId = :id", String.class)
        .setParameter("candidate", candidateId).setParameter("extraction", extractionId).setParameter("id", caseId).getResultList();
    if (versions.isEmpty() || candidates.isEmpty()) throw missing();
    var request = new HumanReviewEntity();
    request.id = UUID.randomUUID().toString(); request.caseId = caseId; request.extractionId = extractionId;
    request.candidateId = candidateId; request.consentVersion = versions.getFirst();
    request.state = "QUEUED"; request.createdAt = Instant.now(); manager.persist(request); manager.flush();
    var result = eligible(request.id);
    audit.audit(caseId, request.id, "BUYER", "REQUESTED");
    return result;
  }
  @Transactional(readOnly = true)
  public List<Map<String, Object>> history(String caseId, String token) {
    cases.reopen(caseId, token);
    var history = projections.rows(METADATA + "where r.caseId = :id order by r.createdAt desc", Map.of("id", caseId));
    history.forEach(row -> {
      if ("STARTED".equals(row.get("state")) && !((Instant) row.get("expiresAt")).isAfter(Instant.now())) row.put("state", "EXPIRED");
    });
    return history;
  }
  @Transactional(readOnly = true)
  public List<Map<String, Object>> queue() {
    return projections.rows(METADATA + "where r.state = 'QUEUED' and " + ELIGIBLE + " order by r.createdAt", Map.of("now", Instant.now()));
  }
  @Transactional
  public Map<String, Object> start(String id, String reviewer) {
    var row = locked(id);
    if (!"QUEUED".equals(row.get("state"))) throw missing();
    Instant expires = Instant.now().plus(duration);
    manager.createQuery("update HumanReviewEntity r set r.state = 'STARTED', r.reviewer = :reviewer, r.expiresAt = :expires where r.id = :id")
        .setParameter("reviewer", reviewer).setParameter("expires", expires).setParameter("id", id).executeUpdate();
    audit.audit((String) row.get("caseId"), id, reviewer, "STARTED");
    return eligible(id);
  }
  @Transactional
  public Map<String, Object> content(String id, String reviewer) {
    var row = assigned(id, reviewer);
    // Sensitive content has its own explicitly authorized projection, scoped to one immutable field.
    var terms = projections.rows("select t.id as id, t.fieldKey as field, t.originalWording as originalWording, t.normalizedValue as normalizedValue, t.normalizedUnit as unit, t.pageNumber as page, t.confidence as confidence from CandidateDealTermEntity t where t.id = :id and t.extractionId = :extraction",
        Map.of("id", row.get("candidateId"), "extraction", row.get("extractionId")));
    if (terms.isEmpty()) throw missing();
    audit.audit((String) row.get("caseId"), id, reviewer, "CONTENT_READ");
    return terms.getFirst();
  }
  @Transactional
  public Map<String, Object> complete(String id, String reviewer, Outcome outcome) {
    var row = assigned(id, reviewer);
    manager.createQuery("update HumanReviewEntity r set r.state = 'COMPLETE', r.outcome = :outcome where r.id = :id")
        .setParameter("id", id).setParameter("outcome", outcome.name()).executeUpdate();
    audit.audit((String) row.get("caseId"), id, reviewer, "COMPLETED");
    return Map.of("id", id, "state", "COMPLETE", "outcome", outcome);
  }
  public enum Outcome { CONFIRMED, NEEDS_BUYER_CORRECTION, UNRESOLVED }
  private Map<String, Object> assigned(String id, String reviewer) {
    var row = locked(id);
    if (!"STARTED".equals(row.get("state")) || !reviewer.equals(row.get("reviewer"))
        || !((Instant) row.get("expiresAt")).isAfter(Instant.now())) throw missing();
    return row;
  }
  private Map<String, Object> locked(String id) {
    var caseIds = manager.createQuery("select r.caseId from HumanReviewEntity r where r.id = :id", String.class).setParameter("id", id).getResultList();
    if (caseIds.isEmpty()) throw missing();
    lockCase(caseIds.getFirst());
    return eligible(id);
  }
  private void lockCase(String id) {
    // A scalar update locks the same row as consent/deletion mutations without loading sensitive columns.
    int updated = manager.createQuery("update AnalysisCaseEntity c set c.lockVersion = c.lockVersion + 1 where c.id = :id and c.lifecycle = 'ACTIVE' and c.accessExpiresAt > :now")
        .setParameter("id", id).setParameter("now", Instant.now()).executeUpdate();
    if (updated != 1) throw missing();
  }
  private Map<String, Object> eligible(String id) {
    var rows = projections.rows(METADATA + "where r.id = :id and " + ELIGIBLE, Map.of("id", id, "now", Instant.now()));
    if (rows.isEmpty()) throw missing();
    return rows.getFirst();
  }
  private static ResourceNotFoundException missing() { return new ResourceNotFoundException("Review not available"); }
}

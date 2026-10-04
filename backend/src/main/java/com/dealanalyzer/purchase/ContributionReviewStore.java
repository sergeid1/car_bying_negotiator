package com.dealanalyzer.purchase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
class ContributionReviewStore {
  private final EntityManager manager;
  private final ObjectMapper json;
  ContributionReviewStore(EntityManager manager, ObjectMapper json) { this.manager = manager; this.json = json; }
  void lock() {
    // Updating the guard also invalidates a competing repeatable-read snapshot.
    // Such a transaction must fail rather than freeze against stale review history.
    try {
      manager.createNativeQuery("update contribution_review_guard set revision = revision + 1 where id = 1").executeUpdate();
    } catch (jakarta.persistence.OptimisticLockException | jakarta.persistence.PessimisticLockException
        | jakarta.persistence.LockTimeoutException conflict) {
      throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT);
    }
  }
  List<ContributionDecisionRecord> records(LocalDate from, LocalDate to, String kind) {
    return manager.createQuery("from ContributionDecisionRecord where windowFrom = :from and windowTo = :to and kind = :kind order by version", ContributionDecisionRecord.class)
        .setParameter("from", from).setParameter("to", to).setParameter("kind", kind).getResultList();
  }
  boolean reviewedOverlap(LocalDate from, LocalDate to) {
    return ((Number) manager.createNativeQuery("select count(*) from contribution_decision_records where record_kind = 'REVIEW' and window_from < :to and window_to > :from")
        .setParameter("from", from).setParameter("to", to).getSingleResult()).longValue() > 0;
  }
  ContributionDecisionRecord append(LocalDate from, LocalDate to, String kind, int version, Object payload) {
    try {
      var record = new ContributionDecisionRecord(from, to, kind, version, json.writeValueAsString(payload));
      manager.persist(record); manager.flush(); return record;
    } catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException(failure); }
  }
  void open(LocalDate from, LocalDate to, Map<String, Object> snapshot) {
    if (records(from, to, "REVIEW").isEmpty()) append(from, to, "REVIEW", 0, snapshot);
  }
  JsonNode payload(ContributionDecisionRecord record) {
    try { return json.readTree(record.payload); }
    catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException(failure); }
  }
  Object view(ContributionDecisionRecord record) {
    return Map.of("version", record.version, "recordedAt", record.createdAt, "payload", payload(record));
  }
}

package com.dealanalyzer.operations;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ReviewConsentLifecycle {
  private final EntityManager manager;
  public ReviewConsentLifecycle(EntityManager manager) { this.manager = manager; }
  /** Runs in the consent transaction, under the same case-row lock used by review reads. */
  @Transactional
  public void invalidate(String caseId) {
    var ids = manager.createQuery("select r.id from HumanReviewEntity r where r.caseId = :id and r.state in ('QUEUED', 'STARTED')", String.class)
        .setParameter("id", caseId).getResultList();
    for (String id : ids) audit(caseId, id, "BUYER", "CONSENT_CHANGED");
    manager.createQuery("update HumanReviewEntity r set r.state = 'REVOKED' where r.caseId = :id and r.state in ('QUEUED', 'STARTED')")
        .setParameter("id", caseId).executeUpdate();
  }
  void audit(String caseId, String reviewId, String actor, String action) {
    var event = new OperatorAuditEntity();
    event.id = UUID.randomUUID().toString(); event.caseId = caseId; event.reviewId = reviewId;
    event.actor = actor; event.action = action; event.occurredAt = Instant.now(); manager.persist(event);
  }
}

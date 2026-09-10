package com.dealanalyzer.buyingcase.persistence;

import com.dealanalyzer.buyingcase.CaseAccessService;
import jakarta.persistence.EntityManager;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CaseRetentionService {
  private final AnalysisCaseRepository cases;
  private final CaseAccessService access;
  private final EntityManager manager;
  private final Duration defaultRetention;
  CaseRetentionService(AnalysisCaseRepository cases, CaseAccessService access, EntityManager manager,
      @Value("${document.retention:PT720H}") Duration defaultRetention) {
    this.cases = cases; this.access = access; this.manager = manager; this.defaultRetention = defaultRetention;
  }

  @Transactional
  public Policy shorten(String id, String token, int days) {
    if (days < 1 || days > 30) throw new org.springframework.web.server.ResponseStatusException(
        org.springframework.http.HttpStatus.BAD_REQUEST, "Choose between 1 and 30 days.");
    var c = access.requireAuthorized(cases.findLockedById(id), token, v -> v.accessTokenHash, v -> v.accessExpiresAt);
    c.documentRetentionDays = c.documentRetentionDays == null ? days : Math.min(c.documentRetentionDays, days);
    Instant chosen = c.createdAt.plus(Duration.ofDays(c.documentRetentionDays));
    if (chosen.isBefore(c.accessExpiresAt)) c.accessExpiresAt = chosen;
    manager.createNativeQuery("update source_documents set retention_deadline = :deadline where case_id = :id and retention_deadline > :deadline")
        .setParameter("deadline", chosen).setParameter("id", id).executeUpdate();
    manager.createNativeQuery("update case_export_jobs set expires_at = :deadline where case_id = :id and expires_at > :deadline")
        .setParameter("deadline", chosen).setParameter("id", id).executeUpdate();
    return new Policy(30, defaultRetention.toSeconds(), c.documentRetentionDays, c.accessExpiresAt);
  }

  @Transactional
  public Instant registerDocumentDeadline(String id, Instant deadline) {
    var c = cases.findLockedById(id).orElseThrow();
    if (!"ACTIVE".equals(c.lifecycle) || !c.accessExpiresAt.isAfter(Instant.now()))
      throw new com.dealanalyzer.buyingcase.ResourceNotFoundException("Buying Case not found");
    if (deadline.isBefore(c.accessExpiresAt)) c.accessExpiresAt = deadline;
    return c.accessExpiresAt;
  }

  @Transactional(readOnly = true)
  public Instant documentDeadline(String id, Instant uploadedAt) {
    var c = cases.findById(id).orElseThrow();
    Instant deadline = uploadedAt.plus(defaultRetention);
    if (c.accessExpiresAt.isBefore(deadline)) deadline = c.accessExpiresAt;
    if (c.documentRetentionDays != null) {
      Instant chosen = c.createdAt.plus(Duration.ofDays(c.documentRetentionDays));
      if (chosen.isBefore(deadline)) deadline = chosen;
    }
    return deadline;
  }
  public record Policy(int maximumDocumentRetentionDays, long defaultDocumentRetentionSeconds,
      int chosenDays, Instant caseExpiresAt) {}
}

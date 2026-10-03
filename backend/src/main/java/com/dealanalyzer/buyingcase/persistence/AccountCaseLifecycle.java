package com.dealanalyzer.buyingcase.persistence;

import com.dealanalyzer.buyingcase.CaseAccessService;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The Buying Case side of a Buyer Account: case-link authorization, case state and deletion. */
@Service
public class AccountCaseLifecycle {
  private final AnalysisCaseRepository cases;
  private final DeletionJobRepository jobs;
  private final CaseDeletionService deletion;
  private final CaseAccessService access;

  AccountCaseLifecycle(AnalysisCaseRepository cases, DeletionJobRepository jobs, CaseDeletionService deletion,
      CaseAccessService access) {
    this.cases = cases; this.jobs = jobs; this.deletion = deletion; this.access = access;
  }

  /** Locks the case for the caller's transaction; wrong, expired or deleted case links get 404. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void requireCaseLink(String caseId, String token) {
    var buyingCase = access.requireAuthorized(cases.findLockedById(caseId), token,
        c -> c.accessTokenHash, c -> c.accessExpiresAt);
    if (!"ACTIVE".equals(buyingCase.lifecycle)) throw new com.dealanalyzer.buyingcase.ResourceNotFoundException("Buying Case not found");
  }

  @Transactional(readOnly = true)
  public String state(String caseId) {
    var job = jobs.findByCaseId(caseId).orElse(null);
    if (job != null) return job.state;
    var buyingCase = cases.findById(caseId).orElse(null);
    if (buyingCase == null) return "DELETED";
    return buyingCase.accessExpiresAt.isAfter(Instant.now()) ? "ACTIVE" : "EXPIRED";
  }

  /** Revokes case access now and queues the case for the standard deletion worker. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void requestDeletion(String caseId, Instant requestedAt) {
    cases.findLockedById(caseId).ifPresent(buyingCase -> deletion.enqueue(buyingCase, "ACCOUNT_DELETION", requestedAt, null));
  }
}

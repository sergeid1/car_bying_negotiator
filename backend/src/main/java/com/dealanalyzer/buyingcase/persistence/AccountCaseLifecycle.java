package com.dealanalyzer.buyingcase.persistence;

import com.dealanalyzer.buyingcase.CaseAccessService;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
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
  private final EntityManager manager;

  AccountCaseLifecycle(AnalysisCaseRepository cases, DeletionJobRepository jobs, CaseDeletionService deletion,
      CaseAccessService access, EntityManager manager) {
    this.cases = cases; this.jobs = jobs; this.deletion = deletion; this.access = access; this.manager = manager;
  }

  /** What the Buyer recognizes a case by in their account. Unknown facts stay null. */
  public record CaseSummary(Instant startedAt, Integer vehicleYear, String vehicleMake, String vehicleModel,
      String vehicleTrim, String vinLastSix, String dealerName, String dealerLocation) {}

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

  /**
   * Issues an account case pass for an open, unexpired case. The caller has already checked the
   * session and the case's attachment to its account; a revoked, expired, deleted or deletion-pending
   * case is refused here like a wrong case link.
   */
  @Transactional(readOnly = true)
  public CaseAccessService.CaseCredential issueAccountPass(String caseId, String sessionHash, Instant notAfter) {
    var buyingCase = cases.findById(caseId).orElseThrow(AccountCaseLifecycle::notFound);
    if (!"ACTIVE".equals(buyingCase.lifecycle) || jobs.findByCaseId(caseId).isPresent()
        || buyingCase.accessTokenHash == null || buyingCase.accessExpiresAt == null
        || !buyingCase.accessExpiresAt.isAfter(Instant.now())) throw notFound();
    Instant expiresAt = notAfter.isBefore(buyingCase.accessExpiresAt) ? notAfter : buyingCase.accessExpiresAt;
    return access.issueAccountPass(caseId, buyingCase.accessTokenHash, sessionHash, expiresAt);
  }

  @Transactional(readOnly = true)
  public Map<String, CaseSummary> summaries(Collection<String> caseIds) {
    var result = new HashMap<String, CaseSummary>();
    if (caseIds.isEmpty()) return result;
    @SuppressWarnings("unchecked")
    var rows = (java.util.List<Object[]>) manager.createNativeQuery("""
        select c.id, c.created_at, v.model_year, v.make, v.model, v.trim_name, v.vin, d.name, c.dealer_location
        from analysis_cases c
        left join vehicles v on v.case_id = c.id
        left join listing_snapshots l on l.id = (select s.id from listing_snapshots s where s.case_id = c.id
          order by s.retrieved_at desc, s.id desc limit 1)
        left join dealers d on d.id = l.dealer_id
        where c.id in (:ids)""").setParameter("ids", caseIds).getResultList();
    for (var row : rows) {
      String vin = (String) row[6];
      result.put((String) row[0], new CaseSummary(instant(row[1]), row[2] == null ? null : ((Number) row[2]).intValue(),
          (String) row[3], (String) row[4], (String) row[5],
          vin == null || vin.length() < 6 ? null : vin.substring(vin.length() - 6), (String) row[7], (String) row[8]));
    }
    return result;
  }

  private static Instant instant(Object value) {
    if (value instanceof Instant instant) return instant;
    if (value instanceof java.time.OffsetDateTime offset) return offset.toInstant();
    if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
    return null;
  }

  private static com.dealanalyzer.buyingcase.ResourceNotFoundException notFound() {
    return new com.dealanalyzer.buyingcase.ResourceNotFoundException("Buying Case not found");
  }

  /** Revokes case access now and queues the case for the standard deletion worker. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void requestDeletion(String caseId, Instant requestedAt) {
    cases.findLockedById(caseId).ifPresent(buyingCase -> deletion.enqueue(buyingCase, "ACCOUNT_DELETION", requestedAt, null));
  }
}

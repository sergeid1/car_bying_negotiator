package com.dealanalyzer.buyingcase.persistence;

import com.dealanalyzer.buyingcase.*;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CaseDeletionService {
  private final DeletionAttemptRepository attempts;
  private final AnalysisCaseRepository cases;
  private final DeletionJobRepository jobs;
  private final CaseAccessService access;

  CaseDeletionService(AnalysisCaseRepository cases, DeletionJobRepository jobs, CaseAccessService access,
      DeletionAttemptRepository attempts) {
    this.attempts = attempts;
    this.cases = cases; this.jobs = jobs; this.access = access;
  }

  @Transactional
  public Receipt request(String caseId, String token) {
    // Serialize against recovery, exports and case writes before revoking credentials.
    var candidate = cases.findLockedById(caseId);
    var previous = jobs.findByCaseId(caseId).orElse(null);
    if (previous != null) return authorizedReceipt(previous, token);
    var buyingCase = access.requireAuthorized(candidate, token, c -> c.accessTokenHash, c -> c.accessExpiresAt);
    return view(enqueue(buyingCase, "BUYER_REQUEST", Instant.now(), receiptHash(token)));
  }

  @Transactional(readOnly = true)
  public Receipt receipt(String caseId, String token) {
    return authorizedReceipt(jobs.findByCaseId(caseId).orElseThrow(this::missing), token);
  }

  DeletionJobEntity enqueue(AnalysisCaseEntity buyingCase, String reason, Instant requestedAt, String hash) {
    var previous = jobs.findByCaseId(buyingCase.id).orElse(null);
    if (previous != null) return previous;
    buyingCase.lifecycle = "DELETION_PENDING";
    buyingCase.accessTokenHash = null;
    buyingCase.accessExpiresAt = Instant.EPOCH;
    var job = new DeletionJobEntity();
    job.id = UUID.randomUUID().toString(); job.caseId = buyingCase.id; job.receiptHash = hash;
    job.state = "DELETION_PENDING"; job.reason = reason; job.requestedAt = requestedAt;
    job.backupStatus = "UNKNOWN";
    return jobs.saveAndFlush(job);
  }

  private Receipt authorizedReceipt(DeletionJobEntity job, String token) {
    if (token == null || job.receiptHash == null || !java.security.MessageDigest.isEqual(
        job.receiptHash.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
        receiptHash(token).getBytes(java.nio.charset.StandardCharsets.US_ASCII))) throw missing();
    return view(job);
  }
  private String receiptHash(String token) { return access.protectRecoveryToken("deletion:" + token); }
  private ResourceNotFoundException missing() { return new ResourceNotFoundException("Deletion receipt not found"); }
  private Receipt view(DeletionJobEntity j) {
    return new Receipt(j.id, j.state, j.requestedAt, j.attempts, j.lastAttemptAt, j.failureCode,
        j.completedAt, j.verification, j.backupStatus, j.backupExpiresAt,
        attempts.findByJobIdOrderByStartedAt(j.id).stream().map(a -> new Attempt(a.startedAt, a.finishedAt,
            a.outcome, a.failureCode, a.verifiedParticipants)).toList());
  }
  public record Receipt(String id, String state, Instant requestedAt, int attempts, Instant lastAttemptAt,
      String failureCode, Instant completedAt, String verification, String backupStatus, Instant backupExpiresAt,
      java.util.List<Attempt> history) {}
  public record Attempt(Instant startedAt, Instant finishedAt, String outcome, String failureCode, int verifiedParticipants) {}
}

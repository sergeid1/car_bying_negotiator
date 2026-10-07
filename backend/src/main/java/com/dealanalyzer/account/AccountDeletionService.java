package com.dealanalyzer.account;

import com.dealanalyzer.buyingcase.CaseAccessService;
import com.dealanalyzer.buyingcase.persistence.AccountCaseLifecycle;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account deletion follows the Buying Case deletion lifecycle: access is revoked at once, every
 * attached case is queued for the case deletion worker, and the account record is removed and
 * verified by {@link AccountDeletionWorker}. See docs/case-deletion-policy.md.
 */
@Service
public class AccountDeletionService {
  private final BuyerAccountRepository accounts;
  private final SignInLinkRepository links;
  private final AccountSessionRepository sessions;
  private final AccountCaseRepository attachments;
  private final AccountDeletionJobRepository jobs;
  private final AccountCaseLifecycle caseLifecycle;
  private final CaseAccessService secrets;
  private final EntityManager manager;

  AccountDeletionService(BuyerAccountRepository accounts, SignInLinkRepository links, AccountSessionRepository sessions,
      AccountCaseRepository attachments, AccountDeletionJobRepository jobs, AccountCaseLifecycle caseLifecycle,
      CaseAccessService secrets, EntityManager manager) {
    this.accounts = accounts; this.links = links; this.sessions = sessions; this.attachments = attachments;
    this.jobs = jobs; this.caseLifecycle = caseLifecycle; this.secrets = secrets; this.manager = manager;
  }

  public record CaseState(String caseId, String state) {}
  public record Receipt(String id, String state, Instant requestedAt, int attempts, Instant lastAttemptAt,
      String failureCode, Instant accountRemovedAt, Instant completedAt, String verification, String backupStatus,
      Instant backupExpiresAt, List<CaseState> cases) {}

  @Transactional
  public Receipt request(String sessionToken) {
    // A replay with the now-revoked session returns the same receipt instead of a new job.
    var previous = receiptJob(sessionToken);
    if (previous != null) return view(previous);
    if (sessionToken == null || sessionToken.isBlank() || sessionToken.length() > 128) throw BuyerAccountService.sessionEnded();
    var session = sessions.findById(secrets.protectAccountSecret("session", sessionToken))
        .filter(s -> s.revokedAt == null && s.expiresAt.isAfter(Instant.now()))
        .orElseThrow(BuyerAccountService::sessionEnded);
    var account = accounts.findLockedById(session.accountId).orElseThrow(BuyerAccountService::sessionEnded);
    if (!"ACTIVE".equals(account.lifecycle)) throw BuyerAccountService.sessionEnded();
    Instant now = Instant.now();
    account.lifecycle = "DELETION_PENDING"; account.updatedAt = now;
    for (var other : sessions.findByAccountIdOrderByCreatedAt(account.id)) if (other.revokedAt == null) other.revokedAt = now;
    for (var link : links.findByAccountIdAndConsumedAtIsNull(account.id)) link.consumedAt = now;
    var job = new AccountDeletionJobEntity(UUID.randomUUID().toString());
    job.accountId = account.id; job.receiptHash = receiptHash(sessionToken); job.state = "DELETION_PENDING";
    job.requestedAt = now; job.backupStatus = "UNKNOWN";
    jobs.saveAndFlush(job);
    for (var attachment : attachments.findByAccountIdOrderByAttachedAt(account.id)) {
      manager.createNativeQuery("insert into buyer_account_deletion_cases (job_id, case_id) values (:job, :caseId)")
          .setParameter("job", job.id).setParameter("caseId", attachment.caseId).executeUpdate();
      caseLifecycle.requestDeletion(attachment.caseId, now);
    }
    // Mailbox tokens go at once, after the cases, so the lock order matches a send (case, then connection).
    for (String table : List.of("mailbox_connections", "mailbox_authorizations"))
      manager.createNativeQuery("delete from " + table + " where account_id = :id").setParameter("id", account.id).executeUpdate();
    return view(job);
  }

  @Transactional(readOnly = true)
  public Receipt receipt(String sessionToken) {
    var job = receiptJob(sessionToken);
    if (job == null) throw new AccountProblem(HttpStatus.NOT_FOUND, "DELETION_RECEIPT_UNKNOWN",
        "No account deletion was requested with this sign-in.");
    return view(job);
  }

  @Transactional(readOnly = true)
  public boolean hasPendingReceipt(String sessionToken) {
    var job = receiptJob(sessionToken);
    return job != null && "DELETION_PENDING".equals(job.state);
  }

  private AccountDeletionJobEntity receiptJob(String sessionToken) {
    if (sessionToken == null || sessionToken.isBlank() || sessionToken.length() > 128) return null;
    return jobs.findByReceiptHash(receiptHash(sessionToken)).orElse(null);
  }

  private String receiptHash(String sessionToken) { return secrets.protectAccountSecret("deletion", sessionToken); }

  List<String> caseIds(String jobId) {
    @SuppressWarnings("unchecked")
    List<String> ids = manager.createNativeQuery(
        "select case_id from buyer_account_deletion_cases where job_id = :job order by case_id")
        .setParameter("job", jobId).getResultList();
    return ids;
  }

  private Receipt view(AccountDeletionJobEntity job) {
    return new Receipt(job.id, job.state, job.requestedAt, job.attempts, job.lastAttemptAt, job.failureCode,
        job.accountRemovedAt, job.completedAt, job.verification, job.backupStatus, job.backupExpiresAt,
        caseIds(job.id).stream().map(id -> new CaseState(id, caseLifecycle.state(id))).toList());
  }
}

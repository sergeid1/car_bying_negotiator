package com.dealanalyzer.account;

import com.dealanalyzer.buyingcase.persistence.AccountCaseLifecycle;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Removes the account record of a pending account deletion, verifies its absence, and completes the
 * job once the case deletion worker has verified every attached case. Also removes sign-up records
 * whose sign-in link expired unused, and expired sign-in links and sessions.
 */
@Service
public class AccountDeletionWorker {
  /** Child-before-parent, all keyed by account ID. No broad ORM cascades. */
  private static final List<String> ACCOUNT_GRAPH = List.of(
      "reference_vehicles", "shopping_brief_versions", "buying_journeys",
      "buyer_notification_preferences", "buyer_notification_deliveries",
      "buyer_account_sessions", "buyer_account_sign_in_links", "buyer_account_cases");

  private final AccountDeletionJobRepository jobs;
  private final AccountDeletionService deletion;
  private final BuyerAccountRepository accounts;
  private final AccountCaseLifecycle caseLifecycle;
  private final EntityManager manager;
  private final TransactionTemplate transaction;
  private final Pageable batch;
  private final Duration linkLifetime;

  AccountDeletionWorker(AccountDeletionJobRepository jobs, AccountDeletionService deletion, BuyerAccountRepository accounts,
      AccountCaseLifecycle caseLifecycle, EntityManager manager, PlatformTransactionManager transactions,
      @Value("${case.deletion.batch-size:100}") int batchSize,
      @Value("${account.sign-in.link-lifetime:PT15M}") Duration linkLifetime) {
    if (batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("Deletion batch must be between 1 and 1000");
    this.jobs = jobs; this.deletion = deletion; this.accounts = accounts; this.caseLifecycle = caseLifecycle;
    this.manager = manager; this.transaction = new TransactionTemplate(transactions);
    this.batch = PageRequest.of(0, batchSize); this.linkLifetime = linkLifetime;
  }

  @Scheduled(fixedDelayString = "${case.deletion.delay:PT5M}", initialDelayString = "${case.deletion.delay:PT5M}")
  public void runOnce() {
    Instant now = Instant.now();
    transaction.executeWithoutResult(status -> {
      for (String id : accounts.findAbandonedSignUps(now.minus(linkLifetime), now, batch)) removeAndVerify(id);
      manager.createNativeQuery("delete from buyer_account_sessions where expires_at <= :now")
          .setParameter("now", now).executeUpdate();
      // Expired links are kept for a day so a late click still gets the "expired" message.
      manager.createNativeQuery("delete from buyer_account_sign_in_links where expires_at <= :cutoff")
          .setParameter("cutoff", now.minus(Duration.ofDays(1))).executeUpdate();
    });
    for (var candidate : jobs.findByStateOrderByRequestedAt("DELETION_PENDING", batch)) {
      transaction.executeWithoutResult(status -> {
        var job = jobs.findLockedById(candidate.id).orElseThrow();
        if (!"DELETION_PENDING".equals(job.state)) return;
        job.attempts++; job.lastAttemptAt = Instant.now();
      });
      try {
        transaction.executeWithoutResult(status -> {
          var job = jobs.findLockedById(candidate.id).orElseThrow();
          if (!"DELETION_PENDING".equals(job.state)) return;
          if (job.accountRemovedAt == null) {
            accounts.findLockedById(job.accountId);
            removeAndVerify(job.accountId);
            job.accountRemovedAt = Instant.now();
          }
          job.failureCode = null;
          // Completion waits for the case deletion worker to verify every attached case.
          if (deletion.caseIds(job.id).stream().allMatch(id -> "DELETED".equals(caseLifecycle.state(id)))) {
            job.state = "DELETED"; job.completedAt = Instant.now();
            job.verification = "ACCOUNT_GRAPH_AND_CASES_ABSENT_V1";
          }
        });
      } catch (RuntimeException failure) {
        transaction.executeWithoutResult(status -> {
          var job = jobs.findLockedById(candidate.id).orElseThrow();
          if (!"DELETED".equals(job.state)) job.failureCode = "REMOVAL_NOT_VERIFIED";
        });
      }
    }
  }

  private void removeAndVerify(String accountId) {
    manager.flush();
    for (String table : ACCOUNT_GRAPH) delete(table, "account_id = :id", accountId);
    delete("buyer_accounts", "id = :id", accountId);
  }

  private void delete(String table, String predicate, String accountId) {
    manager.createNativeQuery("delete from " + table + " where " + predicate).setParameter("id", accountId).executeUpdate();
    long remaining = ((Number) manager.createNativeQuery("select count(*) from " + table + " where " + predicate)
        .setParameter("id", accountId).getSingleResult()).longValue();
    if (remaining != 0) throw new IllegalStateException("Account deletion verification failed");
  }
}

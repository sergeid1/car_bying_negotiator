package com.dealanalyzer.buyingcase.persistence;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Offline restore only: ledger must be exported from the latest deletion record, not the backup. */
@Component
@ConditionalOnProperty(name = "recovery.restore-mode", havingValue = "true")
class RestoreDeletionReconciler implements ApplicationRunner {
  private final Environment environment;
  private final AnalysisCaseRepository cases;
  private final DeletionJobRepository jobs;
  private final CaseDeletionWorker worker;
  private final TransactionTemplate transaction;

  RestoreDeletionReconciler(Environment environment, AnalysisCaseRepository cases,
      DeletionJobRepository jobs, CaseDeletionWorker worker,
      PlatformTransactionManager manager) {
    this.environment = environment; this.cases = cases; this.jobs = jobs;
    this.worker = worker; this.transaction = new TransactionTemplate(manager);
  }

  @Override public void run(ApplicationArguments args) throws Exception {
    if (environment.getProperty("analysis.worker.enabled", Boolean.class, true)
        || environment.getProperty("document.worker.enabled", Boolean.class, true))
      throw new IllegalStateException("Restore requires analysis and document workers to be disabled");
    List<String> ids;
    try {
      Path ledger = Path.of(environment.getRequiredProperty("recovery.deletion-ledger"));
      if (!Files.isRegularFile(ledger) || Files.size(ledger) > 4_000_000) throw new IllegalArgumentException();
      ids = Files.readAllLines(ledger).stream().filter(line -> !line.isBlank()).distinct().toList();
      for (String id : ids) if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
    } catch (Exception invalid) {
      throw new IllegalStateException("Restore requires a readable UUID deletion ledger of at most 4 MB");
    }
    for (String id : ids) transaction.executeWithoutResult(status -> {
      var c = cases.findLockedById(id).orElse(null);
      var job = jobs.findByCaseId(id).orElse(null);
      if (c != null) {
        c.lifecycle = "DELETION_PENDING"; c.accessTokenHash = null; c.accessExpiresAt = Instant.EPOCH;
      }
      // Object snapshots can contain an artifact even when this DB snapshot has no case row.
      if (job == null) {
        job = new DeletionJobEntity(); job.id = UUID.randomUUID().toString(); job.caseId = id;
        job.reason = "RESTORE_RECONCILIATION"; job.requestedAt = Instant.now(); job.backupStatus = "UNKNOWN";
      }
      job.state = "DELETION_PENDING"; job.completedAt = null; job.verification = null;
      jobs.save(job);
    });
    // Reapplies original expiry as well. More than one batch requires additional scheduled runs.
    worker.runOnce();
  }
}

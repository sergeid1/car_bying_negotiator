package com.dealanalyzer.buyingcase.persistence;

import com.dealanalyzer.lifecycle.CaseArtifactErasure;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Service
public class CaseDeletionWorker {
  private final DeletionAttemptRepository attempts;
  private final AnalysisCaseRepository cases;
  private final DeletionJobRepository jobs;
  private final CaseDeletionService deletion;
  private final CaseGraphDeletionPlan graph;
  private final List<CaseArtifactErasure> artifacts;
  private final TransactionTemplate transaction;
  private final org.springframework.data.domain.Pageable batch;

  CaseDeletionWorker(AnalysisCaseRepository cases, DeletionJobRepository jobs, CaseDeletionService deletion,
      CaseGraphDeletionPlan graph, List<CaseArtifactErasure> artifacts, PlatformTransactionManager manager,
      DeletionAttemptRepository attempts,
      @org.springframework.beans.factory.annotation.Value("${case.deletion.batch-size:100}") int batchSize) {
    if (batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("Deletion batch must be between 1 and 1000");
    this.batch = org.springframework.data.domain.PageRequest.of(0, batchSize);
    this.attempts = attempts;
    this.cases = cases; this.jobs = jobs; this.deletion = deletion; this.graph = graph;
    this.artifacts = artifacts; this.transaction = new TransactionTemplate(manager);
  }

  @Scheduled(fixedDelayString = "${case.deletion.delay:PT5M}", initialDelayString = "${case.deletion.delay:PT5M}")
  public void runOnce() {
    Instant now = Instant.now();
    for (String id : cases.findExpiredIds(now, batch)) {
      transaction.executeWithoutResult(status -> {
        var c = cases.findLockedById(id).orElse(null);
        if (c != null && "ACTIVE".equals(c.lifecycle) && !c.accessExpiresAt.isAfter(now))
          deletion.enqueue(c, "RETENTION_EXPIRED", c.accessExpiresAt, null);
      });
    }
    transaction.executeWithoutResult(status -> graph.deleteExpiredExports(now));
    for (var candidate : jobs.findPendingBatch("DELETION_PENDING", batch)) {
      String attemptId = java.util.UUID.randomUUID().toString();
      // Commit attempts before external I/O, so a crash is visible and retryable.
      transaction.executeWithoutResult(status -> {
        var job = jobs.findLockedById(candidate.id).orElseThrow();
        if (!"DELETED".equals(job.state)) {
          job.attempts++; job.lastAttemptAt = Instant.now();
          var attempt = new DeletionAttemptEntity(); attempt.id = attemptId; attempt.jobId = job.id;
          attempt.startedAt = job.lastAttemptAt; attempt.outcome = "STARTED";
          attempts.save(attempt);
        }
      });
      try {
        transaction.executeWithoutResult(status -> {
          cases.findLockedById(candidate.caseId);
          var job = jobs.findLockedById(candidate.id).orElseThrow();
          if ("DELETED".equals(job.state)) {
            attempts.findById(attemptId).ifPresent(a -> { a.outcome = "SUPERSEDED"; a.finishedAt = Instant.now(); });
            return;
          }
          for (var participant : artifacts) participant.eraseAndVerify(job.caseId);
          var attempt = attempts.findById(attemptId).orElseThrow();
          attempt.verifiedParticipants = artifacts.size();
          long started = System.nanoTime();
          job.removedRows = graph.deleteAndVerify(job.caseId);
          job.graphDurationMs = (System.nanoTime() - started) / 1_000_000;
          job.verification = "CASE_GRAPH_AND_ARTIFACTS_ABSENT_V1";
          job.state = "DELETED"; job.completedAt = Instant.now(); job.failureCode = null;
          attempt.finishedAt = job.completedAt; attempt.outcome = "VERIFIED";
        });
      } catch (RuntimeException failure) {
        transaction.executeWithoutResult(status -> {
          var job = jobs.findLockedById(candidate.id).orElseThrow();
          if (!"DELETED".equals(job.state)) {
            job.failureCode = "REMOVAL_NOT_VERIFIED";
            var attempt = attempts.findById(attemptId).orElseThrow();
            attempt.finishedAt = Instant.now(); attempt.outcome = "FAILED"; attempt.failureCode = job.failureCode;
          }
        });
      }
    }
  }
}

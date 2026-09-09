package com.dealanalyzer.buyingcase.persistence;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Operational aggregate, deliberately not exposed through an unauthenticated Buyer endpoint. */
@Service
public class DeletionMeasurements {
  private final DeletionJobRepository jobs;
  DeletionMeasurements(DeletionJobRepository jobs) { this.jobs = jobs; }

  @Transactional(readOnly = true)
  public Snapshot snapshot(Instant now) {
    var completed = new ArrayList<Long>();
    long pending = 0, overdue = 0, oldest = 0;
    for (var job : jobs.findAll()) {
      if ("DELETED".equals(job.state)) completed.add(Duration.between(job.requestedAt, job.completedAt).toMillis());
      else {
        long age = Math.max(0, Duration.between(job.requestedAt, now).toMillis());
        pending++; oldest = Math.max(oldest, age);
        if (age > Duration.ofHours(24).toMillis()) overdue++;
      }
    }
    Collections.sort(completed);
    Long p95 = completed.isEmpty() ? null : completed.get((int) Math.ceil(completed.size() * 0.95) - 1);
    return new Snapshot(completed.size(), p95, pending, overdue, oldest, Duration.ofHours(24).toMillis());
  }
  public record Snapshot(long completedCases, Long completedP95Millis, long pendingCases,
      long overduePendingCases, long oldestPendingMillis, long objectiveMillis) {}
}

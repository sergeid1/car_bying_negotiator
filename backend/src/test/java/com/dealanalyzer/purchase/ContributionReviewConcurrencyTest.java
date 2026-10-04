package com.dealanalyzer.purchase;

import static org.assertj.core.api.Assertions.*;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "spring.datasource.url=jdbc:h2:mem:decision-race;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "case.access.pepper=decision-race-pepper-at-least-thirty-two-bytes",
    "analysis.worker.enabled=false", "document.worker.enabled=false"})
class ContributionReviewConcurrencyTest {
  @Autowired EntityManager manager;
  @Autowired PlatformTransactionManager transactions;
  @Autowired ContributionDecisionService decisions;
  @Autowired ContributionService contributions;
  @Test void freezeWithAStaleSnapshotCannotCommitAfterOutcomeReview() throws Exception {
    var from = LocalDate.of(2026, 1, 1); var to = LocalDate.of(2026, 2, 1);
    var request = new ContributionDecisionService.Freeze(from, to, List.of(
        threshold(ContributionDecisionService.Decision.CONTINUE),
        threshold(ContributionDecisionService.Decision.REPRICE),
        threshold(ContributionDecisionService.Decision.STOP)));
    var snapshotTaken = new CountDownLatch(1);
    var reviewCommitted = new CountDownLatch(1);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var staleFreeze = executor.submit(() -> {
        var transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return catchThrowable(() -> transaction.executeWithoutResult(status -> {
          manager.createNativeQuery("select revision from contribution_review_guard where id = 1").getSingleResult();
          snapshotTaken.countDown();
          try { assertThat(reviewCommitted.await(10, TimeUnit.SECONDS)).isTrue(); }
          catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
          decisions.freeze(request);
        }));
      });
      try {
        assertThat(snapshotTaken.await(10, TimeUnit.SECONDS)).isTrue();
        contributions.snapshot(from, to);
      } finally { reviewCommitted.countDown(); }
      assertThat(staleFreeze.get(15, TimeUnit.SECONDS)).isInstanceOfAny(
          org.springframework.web.server.ResponseStatusException.class,
          org.springframework.dao.ConcurrencyFailureException.class);
      var history = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().valueToTree(decisions.history(from, to));
      assertThat(history.path("thresholds")).isEmpty();
      assertThat(history.path("reviews")).hasSize(1);
    }
  }
  private static ContributionDecisionService.Threshold threshold(ContributionDecisionService.Decision action) {
    return new ContributionDecisionService.Threshold(action, ContributionDecisionService.Metric.PAID_PURCHASE_RATE,
        ContributionDecisionService.Bound.AT_LEAST, new BigDecimal("0.5"), 1);
  }
}

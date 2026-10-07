package com.dealanalyzer.buyingcase.persistence;

import static org.assertj.core.api.Assertions.*;
import com.dealanalyzer.buyingcase.AnalysisWorkflow.OperationStage;
import com.dealanalyzer.buyingcase.SyntheticAnalysisRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// CI supplies one shared PostgreSQL database to this and SchemaAndOrmEvidenceTest.
@org.junit.jupiter.api.Tag("serial")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "spring.datasource.url=${HARDENING_DATABASE_URL:jdbc:h2:mem:hardening;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE}",
    "case.access.pepper=hardening-test-pepper-at-least-thirty-two-bytes",
    "analysis.worker.enabled=false", "document.worker.enabled=false", "case.deletion.delay=PT1H",
    "spring.jpa.properties.hibernate.generate_statistics=true",
    "logging.level.org.hibernate.stat=OFF", "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"})
class PersistenceHardeningTest {
  @Autowired BuyingCaseStore store;
  @Autowired ObjectMapper json;
  @Autowired com.dealanalyzer.document.persistence.DocumentIntakePersistence documents;
  @Autowired PlatformTransactionManager manager;
  @Autowired jakarta.persistence.EntityManagerFactory emf;
  @Autowired AnalysisCaseRepository cases;
  @Autowired EphemeralCasePersistence persistence;

  @Test void staleJpaWriteCannotOverwriteACommittedChange() throws Exception {
    var c = create();
    try (var first = emf.createEntityManager(); var second = emf.createEntityManager()) {
      first.getTransaction().begin(); second.getTransaction().begin();
      var a = first.find(AnalysisCaseEntity.class, c[0]);
      var b = second.find(AnalysisCaseEntity.class, c[0]);
      a.buyerZip = "11111";
      first.getTransaction().commit();
      b.buyerZip = "22222";
      assertThatThrownBy(() -> second.getTransaction().commit())
          .isInstanceOf(jakarta.persistence.RollbackException.class)
          .hasCauseInstanceOf(jakarta.persistence.OptimisticLockException.class);
    }
    assertThat(store.requireCase(c[0]).buyerZip()).isEqualTo("11111");
  }

  @Test void caseLockAfterAnEarlierReadSeesAConcurrentWriteInsteadOfFailing() throws Exception {
    var c = create();
    var outer = new TransactionTemplate(manager);
    var other = new TransactionTemplate(manager);
    other.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    outer.executeWithoutResult(status -> {
      // An ownership check reads the case before the lock, as test-drive approval does.
      assertThat(cases.findById(c[0])).isPresent();
      other.executeWithoutResult(inner -> cases.findById(c[0]).orElseThrow().buyerZip = "11111");
      assertThatCode(() -> persistence.lock(c[0])).doesNotThrowAnyException();
      assertThat(cases.findById(c[0]).orElseThrow().buyerZip).isEqualTo("11111");
    });
    assertThatThrownBy(() -> outer.executeWithoutResult(status -> persistence.lock(UUID.randomUUID().toString())))
        .isInstanceOf(com.dealanalyzer.buyingcase.ResourceNotFoundException.class);
  }

  @Test void redeliveryCannotRewriteACompletedReportOrDowngradeItsState() throws Exception {
    var c = create();
    store.complete(c[0], c[1], 1, "{\"version\":1,\"result\":\"saved\"}", "fingerprint", Instant.now());
    var report = store.requireReport(c[0], Optional.empty());
    store.updateStage(c[1], OperationStage.LOADING_INPUTS, Instant.now());
    store.complete(c[0], c[1], 2, "{\"result\":\"duplicate\"}", "different", Instant.now());
    store.fail(c[0], c[1], "late worker failed", Instant.now());
    assertThat(store.requireCase(c[0]).currentReportVersion()).isEqualTo(1);
    assertThat(store.requireCase(c[0]).status().name()).isEqualTo("COMPLETE");
    assertThat(store.requireOperation(c[1]).stage().name()).isEqualTo("COMPLETE");
    assertThat(store.requireReport(c[0], Optional.empty())).isEqualTo(report);
  }

  @Test void failedReportAssemblyRollsBackAndCanCompleteOnRetry() throws Exception {
    var c = create();
    assertThatThrownBy(() -> store.complete(c[0], c[1], 1, "invalid json", "fingerprint", Instant.now()))
        .isInstanceOf(RuntimeException.class);
    assertThat(store.requireCase(c[0]).currentReportVersion()).isZero();
    assertThat(store.requireOperation(c[1]).stage().name()).isEqualTo("QUEUED");
    store.complete(c[0], c[1], 1, "{}", "fingerprint", Instant.now());
    assertThat(store.requireCase(c[0]).currentReportVersion()).isEqualTo(1);
  }

  @Test void outerTransactionRollbackDoesNotLeaveAnOperationOrChangedCase() throws Exception {
    var c = create();
    String operation = UUID.randomUUID().toString();
    assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
      store.createOperation(operation, c[0], operation, Instant.now());
      throw new IllegalStateException("abort transaction");
    })).isInstanceOf(IllegalStateException.class);
    assertThat(store.findOperationByIdempotencyKey(operation)).isEmpty();
    assertThat(store.requireCase(c[0]).currentReportVersion()).isZero();
  }

  @Test void concurrentIdempotencyRaceCommitsOneOperation() throws Exception {
    var c = create();
    String key = UUID.randomUUID().toString();
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(4)) {
      var gate = new java.util.concurrent.CountDownLatch(1);
      var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
      for (int i = 0; i < 4; i++) futures.add(pool.submit(() -> {
        gate.await();
        try { store.createOperation(UUID.randomUUID().toString(), c[0], key, Instant.now()); return true; }
        catch (org.springframework.dao.DataIntegrityViolationException duplicate) { return false; }
      }));
      gate.countDown();
      int winners = 0;
      for (var future : futures) if (future.get(10, java.util.concurrent.TimeUnit.SECONDS)) winners++;
      assertThat(winners).isEqualTo(1);
      assertThat(store.findOperationByIdempotencyKey(key)).isPresent();
    }
  }

  @Test void reportLookupKeepsTenantPredicateAndQueryCountIndependentOfHistory() throws Exception {
    var c = create(); var other = create();
    for (int i = 1; i <= 12; i++) {
      String op = UUID.randomUUID().toString();
      store.createOperation(op, c[0], op, Instant.now());
      store.complete(c[0], op, i, "{\"saved\":true}", "fingerprint", Instant.now());
    }
    var statistics = emf.unwrap(org.hibernate.SessionFactory.class).getStatistics();
    statistics.clear();
    assertThat(store.requireReport(c[0], Optional.of(12)).path("version").asInt()).isEqualTo(12);
    assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(2);
    assertThatThrownBy(() -> store.requireReport(other[0], Optional.of(12)))
        .isInstanceOf(com.dealanalyzer.buyingcase.ResourceNotFoundException.class);
  }

  @Test
  @org.springframework.transaction.annotation.Transactional
  void recoveryTraversesBoundedBatchesWithoutSkippingRowsAsStagesChange() throws Exception {
    var c = create();
    var expectedAnalysis = new HashSet<String>();
    expectedAnalysis.add(c[1]);
    var expectedDocuments = new HashSet<String>();
    Instant now = Instant.now();
    for (int i = 0; i < 205; i++) {
      String operationId = UUID.randomUUID().toString();
      store.createOperation(operationId, c[0], operationId, now);
      expectedAnalysis.add(operationId);
      String documentId = UUID.randomUUID().toString();
      String documentOperation = UUID.randomUUID().toString();
      documents.create(new com.dealanalyzer.document.persistence.DocumentIntakePersistence.NewDocument(
          documentId, c[0], "test.png", "test/" + documentId, "a".repeat(64), "image/png", 1,
          now.plusSeconds(3600), "test-key", 1, 1, 1, 72, false, false, false, false),
          documentOperation, documentOperation, now);
      if (i % 2 == 0) documents.claimNormalization(documentOperation, now);
      expectedDocuments.add(documentOperation);
    }
    var seenAnalysis = new HashSet<String>();
    String cursor = "";
    while (true) {
      var batch = store.findRecoverableOperations(cursor);
      if (batch.isEmpty()) break;
      assertThat(batch).hasSizeLessThanOrEqualTo(100);
      for (var operation : batch) {
        assertThat(seenAnalysis.add(operation.id())).isTrue();
        store.fail(operation.caseId(), operation.id(), "test failure", now);
      }
      cursor = batch.get(batch.size() - 1).id();
    }
    assertThat(seenAnalysis).containsAll(expectedAnalysis);
    var seenDocuments = new HashSet<String>();
    cursor = "";
    while (true) {
      var batch = documents.recoverInterruptedOperations(cursor, now);
      if (batch.isEmpty()) break;
      assertThat(batch).hasSizeLessThanOrEqualTo(100);
      for (var operation : batch) {
        assertThat(seenDocuments.add(operation.id())).isTrue();
        assertThat(operation.stage()).isEqualTo("QUEUED");
        documents.fail(operation.id(), "QUEUED", "TEST", "test failure", now);
      }
      cursor = batch.get(batch.size() - 1).id();
    }
    assertThat(seenDocuments).containsExactlyInAnyOrderElementsOf(expectedDocuments);
  }

  private String[] create() throws Exception {
    String id = UUID.randomUUID().toString(), op = UUID.randomUUID().toString();
    var input = json.readValue("""
        {"buyerZip":"27601","dealerZip":"27604","vehicle":{"vin":"1HGCM82633A004352",
        "year":2021,"make":"Honda","model":"Accord","trim":"EX-L","mileage":38000},
        "dealerOffer":{"currency":"USD","sellingPriceMinor":2500000,"dealerFeesMinor":79900,
        "addOnsMinor":50000,"taxesMinor":194600,"governmentChargesMinor":23000}}
        """, SyntheticAnalysisRequest.class);
    store.createCase(id, op, op, input, json.writeValueAsString(input), "fingerprint", "hash",
        Instant.now().plusSeconds(3600), Instant.now());
    return new String[] {id, op};
  }
}

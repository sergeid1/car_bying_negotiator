package com.dealanalyzer.operations;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ProviderMeterTest {
  @Test void timeoutAndSaturationStayBoundedEvenWhenAdapterIgnoresInterruption() {
    assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
      var store = new UsageRecorder();
      var meter = new ProviderMeter(store, Duration.ofMillis(100), 1);
      var release = new CountDownLatch(1);
      var started = new CountDownLatch(1);
      var finished = new CountDownLatch(1);
      try {
        assertThatThrownBy(() -> meter.call("case", ProviderMeter.Operation.EXTRACTION, this, 1, () -> {
          started.countDown();
          while (true) {
            try { release.await(); break; }
            catch (InterruptedException ignored) { /* Simulate a broken adapter. */ }
          }
          finished.countDown();
          return "late result";
        }, value -> ProviderMeter.Status.SUCCESS))
            .isInstanceOfSatisfying(ProviderMeter.Failure.class,
                failure -> assertThat(failure.status()).isEqualTo(ProviderMeter.Status.PROVIDER_UNAVAILABLE));
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        var secondInvoked = new AtomicBoolean();
        assertThatThrownBy(() -> meter.call("case", ProviderMeter.Operation.EXTRACTION, this, 1, () -> {
          secondInvoked.set(true); return "unexpected";
        }, value -> ProviderMeter.Status.SUCCESS)).isInstanceOf(ProviderMeter.Failure.class);
        assertThat(secondInvoked).isFalse();
        release.countDown();
        assertThat(finished.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(store.statuses).containsExactly(
            ProviderMeter.Status.PROVIDER_UNAVAILABLE, ProviderMeter.Status.PROVIDER_UNAVAILABLE);
      } finally {
        release.countDown();
        meter.close();
      }
    });
  }

  @Test void successfulAndInvalidResponsesKeepTheirSafeUsageClassification() {
    var store = new UsageRecorder();
    var meter = new ProviderMeter(store, Duration.ofSeconds(1), 8);
    try {
      assertThat(meter.call("case", ProviderMeter.Operation.EXTRACTION, this, 1,
          () -> "result", value -> ProviderMeter.Status.SUCCESS)).isEqualTo("result");
      assertThatThrownBy(() -> meter.call("case", ProviderMeter.Operation.EXTRACTION, this, 1,
          () -> { throw new IllegalStateException("secret provider payload"); },
          value -> ProviderMeter.Status.SUCCESS))
          .isInstanceOf(ProviderMeter.Failure.class).hasMessage("Provider operation failed").hasNoCause();
      assertThatThrownBy(() -> meter.call("case", ProviderMeter.Operation.EXTRACTION, this, 1,
          () -> null, value -> ProviderMeter.Status.SUCCESS)).isInstanceOf(ProviderMeter.Failure.class);
      assertThat(store.statuses).containsExactly(ProviderMeter.Status.SUCCESS,
          ProviderMeter.Status.PROVIDER_FAILURE, ProviderMeter.Status.CONTRACT_FAILURE);
    } finally { meter.close(); }
  }

  @Test void interruptedCallerPreservesInterruptAndClosesUsageRecord() throws Exception {
    var store = new UsageRecorder();
    var meter = new ProviderMeter(store, Duration.ofSeconds(1), 1);
    var release = new CountDownLatch(1);
    try {
      Thread.currentThread().interrupt();
      assertThatThrownBy(() -> meter.call("case", ProviderMeter.Operation.EXTRACTION, this, 1,
          () -> {
            try { release.await(); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            return "result";
          }, value -> ProviderMeter.Status.SUCCESS)).isInstanceOf(ProviderMeter.Failure.class);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      assertThat(store.statuses).containsExactly(ProviderMeter.Status.PROVIDER_UNAVAILABLE);
    } finally { Thread.interrupted(); release.countDown(); meter.close(); }
  }

  private static class UsageRecorder extends ProviderUsageStore {
    final List<ProviderMeter.Status> statuses = new ArrayList<>();
    UsageRecorder() { super(null); }
    @Override public String start(String caseId, ProviderMeter.Operation operation, Object provider, long units) {
      return "usage";
    }
    @Override public void finish(String id, ProviderMeter.Status status) { statuses.add(status); }
  }
}

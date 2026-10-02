package com.dealanalyzer.operations;

import com.dealanalyzer.ai.ModelProvenance;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

@Service
public class ProviderMeter {
  public enum Operation { EXTRACTION, MARKET_RETRIEVAL, LISTING_RETRIEVAL, SYNTHETIC_MARKET, REPLY_DRAFT, EXPLANATION }
  public enum Status { SUCCESS, PROVIDER_UNAVAILABLE, PROVIDER_FAILURE, CONTRACT_FAILURE, BUYER_INPUT, INTERNAL }
  /** A safe boundary error: provider text and exception causes must never escape into diagnostics. */
  public static final class Failure extends RuntimeException {
    private final Status status;
    public Failure(Status status) { super("Provider operation failed"); this.status = status; }
    public Status status() { return status; }
  }
  private final ProviderUsageStore store;
  private final Duration timeout;
  private final ThreadPoolExecutor executor;
  private final Semaphore slots;

  public ProviderMeter(ProviderUsageStore store,
      @Value("${provider.call.timeout:PT30S}") Duration timeout,
      @Value("${provider.call.max-concurrent:8}") int maxConcurrent) {
    if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(3)) > 0
        || maxConcurrent < 1 || maxConcurrent > 64) {
      throw new IllegalArgumentException("Provider timeout must be positive and at most 3 minutes; concurrency must be 1–64");
    }
    this.store = store;
    this.timeout = timeout;
    // No backlog: a call takes a slot or is refused at once. The slot is released only when the
    // adapter actually returns, so an uninterruptible adapter keeps holding it, while a finished call
    // never blocks the next one (a pool without a queue refused calls whose predecessor's worker had
    // not yet returned to it).
    slots = new Semaphore(maxConcurrent);
    executor = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60, TimeUnit.SECONDS,
        new SynchronousQueue<>(), runnable -> {
          var thread = new Thread(runnable, "provider-call");
          thread.setDaemon(true);
          return thread;
        });
  }

  /** The wall-clock budget for one call; model clients use it as their own request timeout. */
  public Duration timeout() { return timeout; }

  @jakarta.annotation.PreDestroy
  public void close() { executor.shutdownNow(); }
  /** Token counts and cost a model vendor reported for one call; cost is null when unpriced. */
  public record ModelUsage(long inputTokens, long outputTokens, BigDecimal costUsd) {
    public ModelUsage {
      if (inputTokens < 0 || outputTokens < 0) throw new IllegalArgumentException("Token counts are non-negative");
    }
  }

  public <T> T call(String caseId, Operation operation, Object provider, long units,
      Supplier<T> invocation, Function<T, Status> classify) {
    return run(store.start(caseId, operation, provider, units), invocation, classify, null);
  }

  /**
   * A model call under the same timeout, concurrency bound and status taxonomy as any provider,
   * additionally recording the output's provenance and the reported tokens and cost. Usage is read
   * from every classified result, including refusals and rejected output, because they are billed.
   */
  public <T> T callModel(String caseId, Operation operation, ModelProvenance provenance, String license,
      long units, Supplier<T> invocation, Function<T, Status> classify, Function<T, ModelUsage> usage) {
    return run(store.startModel(caseId, operation, provenance, license, units), invocation, classify, usage);
  }

  private <T> T run(String id, Supplier<T> invocation, Function<T, Status> classify,
      Function<T, ModelUsage> usage) {
    T result;
    Future<T> pending = null;
    try {
      if (!slots.tryAcquire()) throw new RejectedExecutionException("No provider slot free");
      // The slot is freed inside the call, before its result becomes visible to the caller, so the
      // caller's next call can never find it still taken. The outer release covers a call cancelled
      // before it starts; the flag keeps the two from releasing twice.
      var released = new AtomicBoolean();
      Runnable free = () -> { if (released.compareAndSet(false, true)) slots.release(); };
      var task = new FutureTask<T>(() -> {
        try { return invocation.get(); }
        finally { free.run(); }
      });
      try {
        executor.execute(() -> {
          try { task.run(); }
          finally { free.run(); }
        });
      } catch (RejectedExecutionException closed) {
        free.run();
        throw closed;
      }
      pending = task;
      result = pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
    } catch (TimeoutException | RejectedExecutionException unavailable) {
      if (pending != null) pending.cancel(true);
      store.finish(id, Status.PROVIDER_UNAVAILABLE);
      throw new Failure(Status.PROVIDER_UNAVAILABLE);
    } catch (InterruptedException interrupted) {
      if (pending != null) pending.cancel(true);
      try { store.finish(id, Status.PROVIDER_UNAVAILABLE); }
      finally { Thread.currentThread().interrupt(); }
      throw new Failure(Status.PROVIDER_UNAVAILABLE);
    } catch (ExecutionException failure) {
      store.finish(id, Status.PROVIDER_FAILURE);
      throw new Failure(Status.PROVIDER_FAILURE);
    }
    Status status;
    ModelUsage spent = null;
    try {
      if (result != null && usage != null) spent = usage.apply(result);
      status = result == null ? Status.CONTRACT_FAILURE : classify.apply(result);
    }
    catch (RuntimeException failure) { finish(id, Status.CONTRACT_FAILURE, spent); throw new Failure(Status.CONTRACT_FAILURE); }
    finish(id, status, spent);
    if (result == null) throw new Failure(Status.CONTRACT_FAILURE);
    return result;
  }

  private void finish(String id, Status status, ModelUsage usage) {
    if (usage == null) store.finish(id, status);
    else store.finish(id, status, usage);
  }
}

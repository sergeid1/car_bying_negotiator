package com.dealanalyzer.operations;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

@Service
public class ProviderMeter {
  public enum Operation { EXTRACTION, MARKET_RETRIEVAL, LISTING_RETRIEVAL, SYNTHETIC_MARKET }
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

  public ProviderMeter(ProviderUsageStore store,
      @Value("${provider.call.timeout:PT30S}") Duration timeout,
      @Value("${provider.call.max-concurrent:8}") int maxConcurrent) {
    if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(3)) > 0
        || maxConcurrent < 1 || maxConcurrent > 64) {
      throw new IllegalArgumentException("Provider timeout must be positive and at most 3 minutes; concurrency must be 1–64");
    }
    this.store = store;
    this.timeout = timeout;
    // No backlog: an uninterruptible adapter can occupy only the fixed number of slots.
    executor = new ThreadPoolExecutor(maxConcurrent, maxConcurrent, 0, TimeUnit.SECONDS,
        new SynchronousQueue<>(), runnable -> {
          var thread = new Thread(runnable, "provider-call");
          thread.setDaemon(true);
          return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
  }

  @jakarta.annotation.PreDestroy
  public void close() { executor.shutdownNow(); }
  public <T> T call(String caseId, Operation operation, Object provider, long units,
      Supplier<T> invocation, Function<T, Status> classify) {
    String id = store.start(caseId, operation, provider, units);
    T result;
    Future<T> pending = null;
    try {
      pending = executor.submit(invocation::get);
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
    try { status = result == null ? Status.CONTRACT_FAILURE : classify.apply(result); }
    catch (RuntimeException failure) { store.finish(id, Status.CONTRACT_FAILURE); throw new Failure(Status.CONTRACT_FAILURE); }
    store.finish(id, status);
    if (result == null) throw new Failure(Status.CONTRACT_FAILURE);
    return result;
  }
}

package com.dealanalyzer.operations;

import com.dealanalyzer.ai.ModelProvenance;
import com.dealanalyzer.insurance.quote.InsuranceQuoteAdapter;
import com.dealanalyzer.insurance.quote.InsuranceQuoteContract;
import com.dealanalyzer.insurance.quote.InsuranceQuoteScope;
import com.dealanalyzer.loan.preapproval.LoanApplication;
import com.dealanalyzer.loan.preapproval.PreApprovalAdapter;
import com.dealanalyzer.loan.preapproval.PreApprovalContract;
import com.dealanalyzer.shopping.inventory.InventoryResults;
import com.dealanalyzer.shopping.inventory.InventorySearchAdapter;
import com.dealanalyzer.trade.valuation.TradeValuationAdapter;
import com.dealanalyzer.trade.valuation.TradeValuationContract;
import com.dealanalyzer.trade.valuation.TradeVehicle;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

@Service
public class ProviderMeter {
  public enum Operation { EXTRACTION, MARKET_RETRIEVAL, LISTING_RETRIEVAL, SYNTHETIC_MARKET, REPLY_DRAFT, EXPLANATION, GUIDE_ANSWER, INVENTORY_SEARCH, TRADE_VALUATION, VEHICLE_SAFETY, INSURANCE_QUOTE, PRE_APPROVAL }
  public enum Status { SUCCESS, PROVIDER_UNAVAILABLE, PROVIDER_FAILURE, CONTRACT_FAILURE, BUYER_INPUT, INTERNAL }
  /** A safe boundary error: provider text and exception causes must never escape into diagnostics. */
  public static final class Failure extends RuntimeException {
    private final Status status;
    private final boolean timedOut;
    public Failure(Status status) { this(status, false); }
    private Failure(Status status, boolean timedOut) {
      super("Provider operation failed"); this.status = status; this.timedOut = timedOut;
    }
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

  public <T> T callJourneyMarket(String accountId, String journeyId, Supplier<T> invocation,
      Function<T, Status> classify) {
    return run(store.startJourneyMarket(accountId, journeyId), invocation, classify, null);
  }

  /** Journey-scoped search uses the same concurrency bound and timeout as case-scoped calls. */
  public InventorySearchAdapter.SearchResult callInventory(
      String accountId, String journeyId, InventorySearchAdapter.Provider provider,
      String query, Supplier<InventorySearchAdapter.SearchResult> invocation) {
    String id = store.startInventory(accountId, journeyId, provider, query);
    long started = System.nanoTime();
    try {
      return run(id, invocation, result -> {
        if (!InventoryResults.valid(result, provider))
          throw new IllegalArgumentException("Invalid inventory contract");
        store.inventoryResult(id, result);
        return result.availability() == InventorySearchAdapter.Availability.AVAILABLE
            ? Status.SUCCESS : Status.PROVIDER_UNAVAILABLE;
      }, null);
    } catch (Failure failure) {
      var unavailable = new InventorySearchAdapter.SearchResult(
          failure.timedOut ? InventorySearchAdapter.Availability.TIMED_OUT : InventorySearchAdapter.Availability.UNAVAILABLE,
          provider, Instant.now(), List.of(), null);
      store.inventoryResult(id, unavailable);
      return unavailable;
    } finally {
      store.inventoryLatency(id, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }
  }

  /** Trade valuations share the timeout and concurrency limits; rejected evidence has no range. */
  public TradeValuationAdapter.Result callTradeValuation(String caseId, TradeValuationAdapter.Provider provider,
      TradeVehicle vehicle, Supplier<TradeValuationAdapter.Result> invocation) {
    String id = store.startTradeValuation(caseId, provider);
    long started = System.nanoTime();
    var rejected = new java.util.concurrent.atomic.AtomicReference<TradeValuationAdapter.Reason>();
    var spent = new java.util.concurrent.atomic.AtomicReference<BigDecimal>();
    try {
      return run(id, invocation, result -> {
        spent.set(result.costUsd());
        var failure = TradeValuationContract.failure(result, provider, vehicle, Instant.now());
        if (failure != null) {
          rejected.set(failure);
          throw new IllegalArgumentException("Invalid trade valuation contract");
        }
        store.tradeValuationResult(id, result);
        return result.availability() == TradeValuationAdapter.Availability.AVAILABLE
            ? Status.SUCCESS : Status.PROVIDER_UNAVAILABLE;
      }, null);
    } catch (Failure failure) {
      var reason = rejected.get() != null ? rejected.get() : failure.timedOut
          ? TradeValuationAdapter.Reason.TIMED_OUT : failure.status() == Status.CONTRACT_FAILURE
          ? TradeValuationAdapter.Reason.INVALID_RESPONSE : TradeValuationAdapter.Reason.PROVIDER_UNAVAILABLE;
      var unavailable = TradeValuationContract.unavailable(provider, reason, Instant.now(), spent.get());
      store.tradeValuationResult(id, unavailable);
      return unavailable;
    } finally {
      store.tradeValuationLatency(id, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }
  }

  /** Pre-approval attempts share provider bounds; unsafe offers never leave this boundary. */
  public PreApprovalAdapter.Result callPreApproval(
      String caseId, String accountId, String journeyId,
      PreApprovalAdapter.Provider provider, LoanApplication application,
      Supplier<PreApprovalAdapter.Result> invocation) {
    String id = store.startPreApproval(caseId, accountId, journeyId, provider);
    long started = System.nanoTime();
    var rejected = new java.util.concurrent.atomic.AtomicReference<PreApprovalAdapter.Reason>();
    var spent = new java.util.concurrent.atomic.AtomicReference<BigDecimal>();
    try {
      return run(id, invocation, result -> {
        spent.set(result.costUsd());
        var failure = PreApprovalContract.failure(result, provider, application, Instant.now());
        if (failure != null) {
          rejected.set(failure);
          throw new IllegalArgumentException("Invalid pre-approval contract");
        }
        store.preApprovalResult(id, result);
        return result.availability() == PreApprovalAdapter.Availability.AVAILABLE
            ? Status.SUCCESS : Status.PROVIDER_UNAVAILABLE;
      }, null);
    } catch (Failure failure) {
      var reason = rejected.get() != null ? rejected.get() : failure.timedOut
          ? PreApprovalAdapter.Reason.TIMED_OUT
          : failure.status() == Status.CONTRACT_FAILURE
          ? PreApprovalAdapter.Reason.INVALID_RESPONSE : PreApprovalAdapter.Reason.PROVIDER_UNAVAILABLE;
      var unavailable = PreApprovalContract.unavailable(provider, reason, Instant.now(), spent.get());
      store.preApprovalResult(id, unavailable);
      return unavailable;
    } finally {
      store.preApprovalLatency(id, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }
  }

  /**
   * Insurance quote calls share the timeout and concurrency limits. A case or journey scope is metered;
   * no application detail reaches the usage row, and a rejected response carries no quote.
   */
  public InsuranceQuoteAdapter.Result callInsuranceQuote(InsuranceQuoteScope scope,
      InsuranceQuoteAdapter.Provider provider, Supplier<InsuranceQuoteAdapter.Result> invocation) {
    String id = store.startInsuranceQuote(scope, provider);
    long started = System.nanoTime();
    var rejected = new java.util.concurrent.atomic.AtomicReference<InsuranceQuoteAdapter.Reason>();
    var spent = new java.util.concurrent.atomic.AtomicReference<BigDecimal>();
    try {
      return run(id, invocation, result -> {
        spent.set(result.costUsd());
        var failure = InsuranceQuoteContract.failure(result, provider, Instant.now());
        if (failure != null) {
          rejected.set(failure);
          throw new IllegalArgumentException("Invalid insurance quote contract");
        }
        store.insuranceQuoteResult(id, result);
        return result.availability() == InsuranceQuoteAdapter.Availability.AVAILABLE
            ? Status.SUCCESS : Status.PROVIDER_UNAVAILABLE;
      }, null);
    } catch (Failure failure) {
      var reason = rejected.get() != null ? rejected.get() : failure.timedOut
          ? InsuranceQuoteAdapter.Reason.TIMED_OUT : failure.status() == Status.CONTRACT_FAILURE
          ? InsuranceQuoteAdapter.Reason.INVALID_RESPONSE : InsuranceQuoteAdapter.Reason.PROVIDER_UNAVAILABLE;
      var unavailable = InsuranceQuoteContract.unavailable(provider, reason, Instant.now(), spent.get());
      store.insuranceQuoteResult(id, unavailable);
      return unavailable;
    } finally {
      store.insuranceQuoteLatency(id, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }
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

  public <T> T callModelScoped(ModelUsageScope scope, Operation operation, ModelProvenance provenance, String license,
      long units, Supplier<T> invocation, Function<T, Status> classify, Function<T, ModelUsage> usage) {
    // Preserve the existing case path, including stores implementing that boundary.
    if (scope.caseId() != null)
      return callModel(scope.caseId(), operation, provenance, license, units, invocation, classify, usage);
    return run(store.startModelScoped(scope, operation, provenance, license, units), invocation, classify, usage);
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
    } catch (TimeoutException unavailable) {
      if (pending != null) pending.cancel(true);
      store.finish(id, Status.PROVIDER_UNAVAILABLE);
      throw new Failure(Status.PROVIDER_UNAVAILABLE, true);
    } catch (RejectedExecutionException unavailable) {
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

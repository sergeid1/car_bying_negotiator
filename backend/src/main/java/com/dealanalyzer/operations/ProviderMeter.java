package com.dealanalyzer.operations;

import java.util.function.Function;
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
  public ProviderMeter(ProviderUsageStore store) { this.store = store; }
  public <T> T call(String caseId, Operation operation, Object provider, long units,
      Supplier<T> invocation, Function<T, Status> classify) {
    String id = store.start(caseId, operation, provider, units);
    T result;
    try { result = invocation.get(); }
    catch (RuntimeException failure) { store.finish(id, Status.PROVIDER_FAILURE); throw new Failure(Status.PROVIDER_FAILURE); }
    Status status;
    try { status = result == null ? Status.CONTRACT_FAILURE : classify.apply(result); }
    catch (RuntimeException failure) { store.finish(id, Status.CONTRACT_FAILURE); throw new Failure(Status.CONTRACT_FAILURE); }
    store.finish(id, status);
    if (result == null) throw new Failure(Status.CONTRACT_FAILURE);
    return result;
  }
}

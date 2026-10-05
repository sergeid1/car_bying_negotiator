package com.dealanalyzer.relay;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Deterministic in-memory relay for development and the main test suite. Refused under pilot/production. */
@Component
@ConditionalOnProperty(name = "dealer-relay.provider", havingValue = "fake", matchIfMissing = true)
public class FakeDealerMailRelay implements DealerMailRelay {
  private final List<OutboundMail> sent = new CopyOnWriteArrayList<>();
  private final Map<String, String> accepted = new ConcurrentHashMap<>();
  private final AtomicLong sequence = new AtomicLong();
  private volatile boolean failing;
  private volatile boolean losingResponses;

  @Override public synchronized String send(String idempotencyKey, OutboundMail mail) {
    if (failing) throw new RelayUnavailableException();
    String previous = accepted.get(idempotencyKey);
    if (previous == null) {
      sent.add(mail);
      previous = "fake-" + sequence.incrementAndGet();
      accepted.put(idempotencyKey, previous);
    }
    // The provider accepted the message, but its response never reaches the caller.
    if (losingResponses) throw new IllegalStateException("Relay response lost");
    return previous;
  }

  @Override public Optional<String> lookup(String idempotencyKey) {
    if (failing) throw new RelayUnavailableException();
    return Optional.ofNullable(accepted.get(idempotencyKey));
  }

  @Override public String domain() { return "relay.invalid"; }

  /** Messages handed to the fake, oldest first. A repeated idempotency key adds nothing. */
  public List<OutboundMail> sent() { return List.copyOf(sent); }

  public void clear() { sent.clear(); accepted.clear(); }

  public void failing(boolean failing) { this.failing = failing; }

  /** Accepts each message but throws as if the response was lost, leaving the send unclear. */
  public void losingResponses(boolean losing) { this.losingResponses = losing; }
}

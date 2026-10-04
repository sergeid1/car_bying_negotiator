package com.dealanalyzer.relay;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Deterministic in-memory relay for development and the main test suite. Refused under pilot/production. */
@Component
@ConditionalOnProperty(name = "dealer-relay.provider", havingValue = "fake", matchIfMissing = true)
public class FakeDealerMailRelay implements DealerMailRelay {
  private final List<OutboundMail> sent = new CopyOnWriteArrayList<>();
  private final AtomicLong sequence = new AtomicLong();
  private volatile boolean failing;

  @Override public String send(OutboundMail mail) {
    if (failing) throw new RelayUnavailableException();
    sent.add(mail);
    return "fake-" + sequence.incrementAndGet();
  }

  @Override public String domain() { return "relay.invalid"; }

  /** Messages handed to the fake, oldest first. */
  public List<OutboundMail> sent() { return List.copyOf(sent); }

  public void clear() { sent.clear(); }

  public void failing(boolean failing) { this.failing = failing; }
}

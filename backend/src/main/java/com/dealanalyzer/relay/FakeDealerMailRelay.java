package com.dealanalyzer.relay;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Development and test reply intake: issues addresses under a reserved domain that no mail can reach. Tests
 * deliver mail by calling {@link RelayService#receive}. Refused under pilot/production.
 */
@Component
@ConditionalOnProperty(name = "dealer-relay.provider", havingValue = "fake", matchIfMissing = true)
public class FakeDealerMailRelay implements DealerMailRelay {
  @Override public String domain() { return "relay.invalid"; }
}

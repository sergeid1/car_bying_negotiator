package com.dealanalyzer.relay;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** No inbound provider configured: reply-intake addresses are issued but no mail can arrive. */
@Component
@ConditionalOnProperty(name = "dealer-relay.provider", havingValue = "none")
class UnavailableDealerMailRelay implements DealerMailRelay {
  @Override public String domain() { return "relay.invalid"; }
}

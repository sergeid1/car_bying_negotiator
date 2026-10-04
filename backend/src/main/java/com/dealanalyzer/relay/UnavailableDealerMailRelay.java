package com.dealanalyzer.relay;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** No provider configured: every send is refused. Inbound mail cannot arrive without a provider. */
@Component
@ConditionalOnProperty(name = "dealer-relay.provider", havingValue = "none")
class UnavailableDealerMailRelay implements DealerMailRelay {
  @Override public String send(OutboundMail mail) { throw new RelayUnavailableException(); }
  @Override public String domain() { return "relay.invalid"; }
}

package com.dealanalyzer.relay;

import com.dealanalyzer.deployment.DeploymentStage;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The reply-intake address accepts mail only through an authenticated inbound provider. Ticket 78's prerequisite
 * (a verified provider webhook signature or equivalent, replay protection, and binding each message to its case's
 * address) must be met before any real provider is enabled, so production-like stages refuse the fake. No real
 * provider exists in this build; production runs with {@code dealer-relay.provider=none}.
 */
@Component
class RelayProductionGuard implements SmartInitializingSingleton {
  private final DeploymentStage stage;
  private final DealerMailRelay relay;

  RelayProductionGuard(Environment environment, DealerMailRelay relay) {
    this.stage = DeploymentStage.from(environment); this.relay = relay;
  }

  @Override public void afterSingletonsInstantiated() {
    if (stage.productionLike() && relay instanceof FakeDealerMailRelay)
      throw new IllegalStateException("The fake reply-intake provider cannot serve production; set dealer-relay.provider");
  }
}

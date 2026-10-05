package com.dealanalyzer.relay;

import com.dealanalyzer.deployment.DeploymentStage;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** The fake relay must never reach a dealer through a profile change. */
@Component
class RelayProductionGuard implements SmartInitializingSingleton {
  private final DeploymentStage stage;
  private final DealerMailRelay relay;

  RelayProductionGuard(Environment environment, DealerMailRelay relay) {
    this.stage = DeploymentStage.from(environment); this.relay = relay;
  }

  @Override public void afterSingletonsInstantiated() {
    if (stage.productionLike() && relay instanceof FakeDealerMailRelay)
      throw new IllegalStateException("The fake dealer email relay cannot serve production; set dealer-relay.provider");
  }
}

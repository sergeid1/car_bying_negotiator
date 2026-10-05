package com.dealanalyzer.purchase;

import com.dealanalyzer.deployment.DeploymentStage;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public final class CheckoutProductionGuard implements SmartInitializingSingleton {
  private final DeploymentStage stage;
  private final CheckoutProvider checkout;
  public CheckoutProductionGuard(Environment environment, CheckoutProvider checkout) {
    this.stage = DeploymentStage.from(environment); this.checkout = checkout;
  }
  public void afterSingletonsInstantiated() {
    if (stage.productionLike() && checkout instanceof DevelopmentNoChargeCheckout)
      throw new IllegalStateException("Development checkout adapter cannot serve production: DevelopmentNoChargeCheckout");
  }
}

package com.dealanalyzer.shopping.inventory;

import com.dealanalyzer.deployment.DeploymentStage;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class InventorySearchProductionGuard implements SmartInitializingSingleton {
  private final DeploymentStage stage;
  private final InventorySearchAdapter adapter;
  public InventorySearchProductionGuard(Environment environment, InventorySearchAdapter adapter) {
    this.stage = DeploymentStage.from(environment); this.adapter = adapter;
  }
  @Override public void afterSingletonsInstantiated() {
    if (stage.productionLike() && (adapter instanceof DeterministicFakeInventorySearchAdapter || adapter.provider().synthetic()))
      throw new IllegalStateException("Development inventory adapter cannot serve production: "
          + org.springframework.aop.support.AopUtils.getTargetClass(adapter).getSimpleName());
  }
}

package com.dealanalyzer.trade.valuation;

import com.dealanalyzer.deployment.DeploymentStage;
import com.dealanalyzer.market.DevelopmentEvidenceAdapter;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Uses the shared evidence deployment decision and development-adapter marker. */
@Component
public class TradeValuationProductionGuard implements SmartInitializingSingleton {
  private final DeploymentStage stage;
  private final TradeValuationAdapter adapter;
  public TradeValuationProductionGuard(Environment environment, TradeValuationAdapter adapter) {
    this.stage = DeploymentStage.from(environment); this.adapter = adapter;
  }
  @Override public void afterSingletonsInstantiated() {
    if (stage.productionLike() && (adapter instanceof DevelopmentEvidenceAdapter || adapter.provider().synthetic()))
      throw new IllegalStateException("Development trade valuation adapter cannot serve production: "
          + org.springframework.aop.support.AopUtils.getTargetClass(adapter).getSimpleName());
  }
}

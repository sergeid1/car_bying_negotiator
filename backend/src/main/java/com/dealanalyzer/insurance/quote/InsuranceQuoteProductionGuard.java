package com.dealanalyzer.insurance.quote;

import com.dealanalyzer.deployment.DeploymentStage;
import com.dealanalyzer.market.DevelopmentEvidenceAdapter;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Uses the shared evidence deployment decision and development-adapter marker. */
@Component
public class InsuranceQuoteProductionGuard implements SmartInitializingSingleton {
  private final DeploymentStage stage;
  private final InsuranceQuoteAdapter adapter;
  public InsuranceQuoteProductionGuard(Environment environment, InsuranceQuoteAdapter adapter) {
    this.stage = DeploymentStage.from(environment); this.adapter = adapter;
  }
  @Override public void afterSingletonsInstantiated() {
    if (stage.productionLike() && (adapter instanceof DevelopmentEvidenceAdapter || adapter.provider().synthetic()))
      throw new IllegalStateException("Development insurance quote adapter cannot serve production: "
          + org.springframework.aop.support.AopUtils.getTargetClass(adapter).getSimpleName());
  }
}

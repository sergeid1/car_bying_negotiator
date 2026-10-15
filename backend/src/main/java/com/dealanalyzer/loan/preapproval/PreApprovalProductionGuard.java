package com.dealanalyzer.loan.preapproval;

import com.dealanalyzer.deployment.DeploymentStage;
import com.dealanalyzer.market.DevelopmentEvidenceAdapter;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class PreApprovalProductionGuard implements SmartInitializingSingleton {
  private final DeploymentStage stage;
  private final PreApprovalAdapter adapter;
  public PreApprovalProductionGuard(Environment environment, PreApprovalAdapter adapter) {
    this.stage = DeploymentStage.from(environment); this.adapter = adapter;
  }
  @Override public void afterSingletonsInstantiated() {
    if (stage.productionLike() && (adapter instanceof DevelopmentEvidenceAdapter || adapter.provider().synthetic()))
      throw new IllegalStateException("Development pre-approval adapter cannot serve production: "
          + org.springframework.aop.support.AopUtils.getTargetClass(adapter).getSimpleName());
  }
}

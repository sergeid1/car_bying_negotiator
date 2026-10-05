package com.dealanalyzer.market;

import com.dealanalyzer.listing.ListingEvidenceProvider;
import com.dealanalyzer.deployment.DeploymentStage;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Development evidence must never become production evidence through a profile change. */
@Component
public final class EvidenceProviderProductionGuard implements SmartInitializingSingleton {
  private final DeploymentStage stage;
  private final MarketDataProvider market;
  private final ListingEvidenceProvider listing;

  public EvidenceProviderProductionGuard(
      Environment environment, MarketDataProvider market, ListingEvidenceProvider listing) {
    this.stage = DeploymentStage.from(environment);
    this.market = market;
    this.listing = listing;
  }

  @Override
  public void afterSingletonsInstantiated() {
    if (!stage.productionLike()) return;
    for (Object adapter : new Object[] {market, listing}) {
      if (adapter instanceof DevelopmentEvidenceAdapter) {
        throw new IllegalStateException("Development evidence adapter cannot serve production: "
            + org.springframework.aop.support.AopUtils.getTargetClass(adapter).getSimpleName());
      }
    }
  }
}

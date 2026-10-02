package com.dealanalyzer.market;

import com.dealanalyzer.listing.ListingEvidenceProvider;
import java.util.Locale;
import java.util.stream.Stream;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Development evidence must never become production evidence through a profile change. */
@Component
public final class EvidenceProviderProductionGuard implements SmartInitializingSingleton {
  private final Environment environment;
  private final MarketDataProvider market;
  private final ListingEvidenceProvider listing;

  public EvidenceProviderProductionGuard(
      Environment environment, MarketDataProvider market, ListingEvidenceProvider listing) {
    this.environment = environment;
    this.market = market;
    this.listing = listing;
  }

  @Override
  public void afterSingletonsInstantiated() {
    boolean production = Stream.concat(
        Stream.of(environment.getActiveProfiles()),
        Stream.of(environment.getProperty("evidence.deployment", "development")))
        .map(value -> value.toLowerCase(Locale.ROOT))
        .anyMatch(value -> value.equals("production") || value.equals("prod") || value.equals("pilot"));
    if (!production) return;
    for (Object adapter : new Object[] {market, listing}) {
      if (adapter instanceof DevelopmentEvidenceAdapter) {
        throw new IllegalStateException("Development evidence adapter cannot serve production: "
            + org.springframework.aop.support.AopUtils.getTargetClass(adapter).getSimpleName());
      }
    }
  }
}

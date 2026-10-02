package com.dealanalyzer.market;

import static org.assertj.core.api.Assertions.assertThat;
import com.dealanalyzer.listing.ListingEvidenceProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

class EvidenceProviderProductionGuardTest {
  private final ApplicationContextRunner context = new ApplicationContextRunner()
      .withUserConfiguration(Adapters.class);

  @Test void developmentStartsWithBothFixtures() {
    context.run(application -> assertThat(application).hasNotFailed());
  }

  @Test void everyProductionProfileFailsEvenWithDevelopmentProperty() {
    for (String profile : new String[] {"production", "prod", "pilot"})
      context.withPropertyValues("spring.profiles.active=" + profile, "evidence.deployment=development")
          .run(application -> assertThat(application.getStartupFailure())
              .hasStackTraceContaining("DeterministicFakeMarketDataProvider"));
  }

  @Test void productionDeploymentPropertyFails() {
    context.withPropertyValues("evidence.deployment=production").run(application ->
        assertThat(application.getStartupFailure()).hasStackTraceContaining("DeterministicFakeMarketDataProvider"));
  }

  @Test void listingCannotServeProductionWhenMarketAdapterIsReplaced() {
    context.withBean("realMarket", MarketDataProvider.class,
        () -> org.mockito.Mockito.mock(MarketDataProvider.class), definition -> definition.setPrimary(true))
        .withPropertyValues("evidence.deployment=production").run(application ->
            assertThat(application.getStartupFailure()).hasStackTraceContaining("DeterministicListingEvidenceProvider"));
  }

  @Configuration(proxyBeanMethods = false)
  @ComponentScan(basePackages = {"com.dealanalyzer.market", "com.dealanalyzer.listing"},
      useDefaultFilters = false, includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
          classes = {MarketDataProvider.class, ListingEvidenceProvider.class, EvidenceProviderProductionGuard.class}))
  static class Adapters {}
}

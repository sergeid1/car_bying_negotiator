package com.dealanalyzer.insurance.quote;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class InsuranceQuoteProductionGuardTest {
  private final ApplicationContextRunner context = new ApplicationContextRunner()
      .withBean(InsuranceQuoteAdapter.class, DeterministicFakeInsuranceQuoteAdapter::new)
      .withBean(InsuranceQuoteProductionGuard.class);
  @Test void developmentAllowsTheFake() { context.run(app -> assertThat(app).hasNotFailed()); }
  @Test void pilotAndProductionCannotBeOverriddenByDevelopment() {
    for (String profile : new String[] {"pilot", "prod", "production"})
      context.withPropertyValues("spring.profiles.active=" + profile, "evidence.deployment=development")
          .run(app -> assertThat(app.getStartupFailure()).hasStackTraceContaining("DeterministicFakeInsuranceQuoteAdapter"));
    context.withPropertyValues("evidence.deployment=production").run(app -> assertThat(app).hasFailed());
  }
  @Test void anySyntheticReplacementIsRefusedAndLicensedReplacementCanStart() {
    for (boolean synthetic : new boolean[] {true, false})
      new ApplicationContextRunner().withBean(InsuranceQuoteProductionGuard.class)
          .withBean(InsuranceQuoteAdapter.class, () -> {
            var adapter = org.mockito.Mockito.mock(InsuranceQuoteAdapter.class);
            org.mockito.Mockito.when(adapter.provider()).thenReturn(new InsuranceQuoteAdapter.Provider("TEST", "v1", "TEST_TERMS", synthetic));
            return adapter;
          }).withPropertyValues("spring.profiles.active=pilot")
          .run(app -> { if (synthetic) assertThat(app).hasFailed(); else assertThat(app).hasNotFailed(); });
  }
}

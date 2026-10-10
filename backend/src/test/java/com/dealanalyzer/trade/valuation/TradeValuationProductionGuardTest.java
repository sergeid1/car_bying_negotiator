package com.dealanalyzer.trade.valuation;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class TradeValuationProductionGuardTest {
  private final ApplicationContextRunner context = new ApplicationContextRunner()
      .withBean(TradeValuationAdapter.class, DeterministicFakeTradeValuationAdapter::new)
      .withBean(TradeValuationProductionGuard.class);
  @Test void developmentAllowsTheFake() { context.run(app -> assertThat(app).hasNotFailed()); }
  @Test void pilotAndProductionCannotBeOverriddenByDevelopment() {
    for (String profile : new String[] {"pilot", "prod", "production"})
      context.withPropertyValues("spring.profiles.active=" + profile, "evidence.deployment=development")
          .run(app -> assertThat(app.getStartupFailure()).hasStackTraceContaining("DeterministicFakeTradeValuationAdapter"));
    context.withPropertyValues("evidence.deployment=production").run(app -> assertThat(app).hasFailed());
  }
  @Test void anySyntheticReplacementIsRefusedAndLicensedReplacementCanStart() {
    for (boolean synthetic : new boolean[] {true, false})
      new ApplicationContextRunner().withBean(TradeValuationProductionGuard.class)
          .withBean(TradeValuationAdapter.class, () -> {
            var adapter = org.mockito.Mockito.mock(TradeValuationAdapter.class);
            org.mockito.Mockito.when(adapter.provider()).thenReturn(new TradeValuationAdapter.Provider("TEST", "v1", "TEST_TERMS", synthetic));
            return adapter;
          }).withPropertyValues("spring.profiles.active=pilot")
          .run(app -> { if (synthetic) assertThat(app).hasFailed(); else assertThat(app).hasNotFailed(); });
  }
}

package com.dealanalyzer.shopping.inventory;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class InventorySearchProductionGuardTest {
  private final ApplicationContextRunner context = new ApplicationContextRunner()
      .withBean(InventorySearchAdapter.class, DeterministicFakeInventorySearchAdapter::new)
      .withBean(InventorySearchProductionGuard.class);
  @Test void developmentAllowsTheFake() { context.run(app -> assertThat(app).hasNotFailed()); }
  @Test void productionProfilesCannotBeOverriddenToDevelopment() {
    for (String profile : new String[] {"pilot", "prod", "production"})
      context.withPropertyValues("spring.profiles.active=" + profile, "inventory.deployment=development")
          .run(app -> assertThat(app.getStartupFailure()).hasStackTraceContaining("DeterministicFakeInventorySearchAdapter"));
  }
  @Test void productionDeploymentPropertiesAlsoRefuseTheFake() {
    for (String property : new String[] {"inventory.deployment=production", "evidence.deployment=production"})
      context.withPropertyValues(property).run(app -> assertThat(app).hasFailed());
  }
  @Test void replacementNonSyntheticAdapterMayStartInPilot() {
    new ApplicationContextRunner().withBean(InventorySearchProductionGuard.class)
        .withBean(InventorySearchAdapter.class, () -> {
          var adapter = org.mockito.Mockito.mock(InventorySearchAdapter.class);
          org.mockito.Mockito.when(adapter.provider()).thenReturn(new InventorySearchAdapter.Provider(
              "LICENSED_TEST", "v1", "EXECUTED_TERMS_TEST", false));
          return adapter;
        }).withPropertyValues("spring.profiles.active=pilot").run(app -> assertThat(app).hasNotFailed());
  }
}

package com.dealanalyzer.api;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ClientAddressProductionGuardTest {
  private static final String PROPERTY = "security.client-address.origin-secret";

  @Test void everyDeploymentDeclarationRefusesAMissingOriginSecret() {
    for (String declaration : new String[]{"profile", "evidence.deployment", "checkout.deployment", "inventory.deployment"}) {
      for (String stage : new String[]{"pilot", "production", "prod"}) {
        for (String secret : new String[]{null, ""}) {
          var environment = new MockEnvironment();
          if (secret != null) environment.setProperty(PROPERTY, secret);
          if (declaration.equals("profile")) environment.setActiveProfiles(stage); else environment.setProperty(declaration, stage);
          var refused = assertThrows(IllegalStateException.class, () -> new ClientAddressProductionGuard(environment));
          assertTrue(refused.getMessage().contains(PROPERTY));
        }
      }
    }
  }

  @Test void aConfiguredSecretStartsAndDevelopmentMayLeaveItEmpty() {
    var pilot = new MockEnvironment().withProperty(PROPERTY, "d".repeat(64));
    pilot.setActiveProfiles("pilot");
    assertDoesNotThrow(() -> new ClientAddressProductionGuard(pilot));
    assertDoesNotThrow(() -> new ClientAddressProductionGuard(new MockEnvironment()
        .withProperty("evidence.deployment", "production").withProperty(PROPERTY, "d".repeat(64))));
    assertDoesNotThrow(() -> new ClientAddressProductionGuard(new MockEnvironment()));
    assertDoesNotThrow(() -> new ClientAddressProductionGuard(new MockEnvironment().withProperty(PROPERTY, "")));
  }
}

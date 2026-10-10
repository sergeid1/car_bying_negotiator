package com.dealanalyzer.recovery;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class RecoveryEmailProductionGuardTest {
  @Test void liveEmailRequiresValidFeedbackTopicsUnderEveryStageDeclaration() {
    for (String declaration : new String[]{"profile", "evidence.deployment", "checkout.deployment", "inventory.deployment"}) {
      for (String topics : new String[]{"", "not-an-arn", "arn:aws:sns:us-east-1:123456789012:feedback,"}) {
        var environment = new MockEnvironment().withProperty("case.recovery.from", "sender@example.com")
            .withProperty("spring.mail.host", "email-smtp.us-east-1.amazonaws.com").withProperty("email.ses.topic-arns", topics);
        if (declaration.equals("profile")) environment.setActiveProfiles("pilot"); else environment.setProperty(declaration, "production");
        assertThrows(IllegalStateException.class, () -> new RecoveryEmailProductionGuard(environment));
      }
    }
  }
  @Test void everyDeploymentDeclarationRefusesLocalMailWhenEnabled() {
    for (String declaration : new String[]{"profile", "evidence.deployment", "checkout.deployment", "inventory.deployment"}) {
      for (String stage : new String[]{"pilot", "production", "prod"}) {
        for (String host : new String[]{"", "localhost", "127.0.0.2", "[::1]", "mailpit", "mailhog", "mailcatcher", "host.docker.internal"}) {
          var environment = new MockEnvironment().withProperty("case.recovery.from", "sender@example.com")
              .withProperty("spring.mail.host", host);
          if (declaration.equals("profile")) environment.setActiveProfiles(stage);
          else environment.setProperty(declaration, stage);
          assertThrows(IllegalStateException.class, () -> new RecoveryEmailProductionGuard(environment));
        }
      }
    }
  }

  @Test void developmentMailAndRealProductionMailAreAllowed() {
    assertDoesNotThrow(() -> new RecoveryEmailProductionGuard(new MockEnvironment()
        .withProperty("case.recovery.from", "local@localhost.test").withProperty("spring.mail.host", "mailpit")));
    assertDoesNotThrow(() -> new RecoveryEmailProductionGuard(new MockEnvironment()
        .withProperty("evidence.deployment", "production").withProperty("case.recovery.from", "sender@example.com")
        .withProperty("spring.mail.host", "smtp.example.com")
        .withProperty("email.ses.topic-arns", "arn:aws:sns:us-east-1:123456789012:feedback")));
    assertDoesNotThrow(() -> new RecoveryEmailProductionGuard(new MockEnvironment().withProperty("evidence.deployment", "pilot")));
  }
}

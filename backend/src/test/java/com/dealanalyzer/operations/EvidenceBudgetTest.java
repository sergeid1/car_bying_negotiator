package com.dealanalyzer.operations;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class EvidenceBudgetTest {
  private final ApplicationContextRunner context = new ApplicationContextRunner()
      .withBean(EvidenceBudget.class);

  @Test void developmentReportsUnsetWithoutInventingValues() {
    context.run(app -> {
      assertThat(app).hasNotFailed();
      assertThat(app.getBean(EvidenceBudget.class).view()).isEqualTo(new EvidenceBudget.View("UNSET", null, null));
    });
  }

  @Test void productionRequiresBothCeilingsAndDevelopmentCannotOverrideAProfile() {
    for (String deployment : new String[] {"spring.profiles.active=pilot", "spring.profiles.active=production",
        "spring.profiles.active=prod", "evidence.deployment=production"}) {
      context.withPropertyValues("evidence.deployment=development", deployment)
          .run(app -> assertThat(app.getStartupFailure()).hasStackTraceContaining("Evidence budget must configure"));
      context.withPropertyValues(deployment, "evidence.budget.per-case-cost-usd=0.25")
          .run(app -> assertThat(app).hasFailed());
      context.withPropertyValues(deployment, "evidence.budget.p95-latency-millis=1000")
          .run(app -> assertThat(app).hasFailed());
      context.withPropertyValues(deployment, "evidence.budget.per-case-cost-usd=0.25",
          "evidence.budget.p95-latency-millis=1000").run(app -> assertThat(app).hasNotFailed());
    }
  }

  @Test void invalidBudgetsFailEvenInDevelopment() {
    for (String property : new String[] {"evidence.budget.per-case-cost-usd=-1",
        "evidence.budget.p95-latency-millis=0", "evidence.budget.p95-latency-millis=-1",
        "evidence.budget.per-case-cost-usd=invalid"}) {
      context.withPropertyValues(property).run(app -> assertThat(app).hasFailed());
    }
  }
}

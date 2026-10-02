package com.dealanalyzer.operations;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.stream.Stream;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Deployment-owned ceilings; no fixture price or latency is a pilot approval. */
@Component
public final class EvidenceBudget implements SmartInitializingSingleton {
  private final Environment environment;
  private final BigDecimal perCaseCostUsd;
  private final Long p95LatencyMillis;

  public EvidenceBudget(Environment environment) {
    this.environment = environment;
    perCaseCostUsd = environment.getProperty("evidence.budget.per-case-cost-usd", BigDecimal.class);
    p95LatencyMillis = environment.getProperty("evidence.budget.p95-latency-millis", Long.class);
    if (perCaseCostUsd != null && perCaseCostUsd.signum() < 0
        || p95LatencyMillis != null && p95LatencyMillis <= 0) {
      throw new IllegalArgumentException("Evidence budget requires non-negative USD cost and positive latency milliseconds");
    }
  }

  public record View(String status, BigDecimal perCaseCostUsd, Long p95LatencyMillis) {}

  public View view() {
    return new View(perCaseCostUsd != null && p95LatencyMillis != null ? "CONFIGURED" : "UNSET",
        perCaseCostUsd, p95LatencyMillis);
  }

  @Override public void afterSingletonsInstantiated() {
    boolean production = Stream.concat(Stream.of(environment.getActiveProfiles()),
        Stream.of(environment.getProperty("evidence.deployment", "development")))
        .map(value -> value.toLowerCase(Locale.ROOT))
        .anyMatch(value -> value.equals("production") || value.equals("prod") || value.equals("pilot"));
    if (production && view().status().equals("UNSET")) {
      throw new IllegalStateException("Evidence budget must configure evidence.budget.per-case-cost-usd and evidence.budget.p95-latency-millis for pilot/production");
    }
  }
}

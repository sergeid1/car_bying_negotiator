package com.dealanalyzer.deployment;

import java.util.Locale;
import java.util.stream.Stream;
import org.springframework.core.env.Environment;

/** Immutable deployment decision shared by every development-only guard. */
public record DeploymentStage(boolean productionLike) {
  /** Legacy aliases remain restrictive; no declaration can weaken another declaration. */
  public static DeploymentStage from(Environment environment) {
    return new DeploymentStage(Stream.concat(Stream.of(environment.getActiveProfiles()),
        Stream.of(environment.getProperty("evidence.deployment", "development"),
            environment.getProperty("checkout.deployment", "development"),
            environment.getProperty("inventory.deployment", "development")))
        .map(value -> value.trim().toLowerCase(Locale.ROOT))
        .anyMatch(value -> value.equals("pilot") || value.equals("production") || value.equals("prod")));
  }
}

package com.dealanalyzer.shopping.inventory;

import java.util.Locale;
import java.util.stream.Stream;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class InventorySearchProductionGuard implements SmartInitializingSingleton {
  private final Environment environment;
  private final InventorySearchAdapter adapter;
  public InventorySearchProductionGuard(Environment environment, InventorySearchAdapter adapter) {
    this.environment = environment; this.adapter = adapter;
  }
  @Override public void afterSingletonsInstantiated() {
    boolean production = Stream.concat(Stream.of(environment.getActiveProfiles()),
        Stream.of(environment.getProperty("inventory.deployment", "development"),
            environment.getProperty("evidence.deployment", "development")))
        .map(value -> value.toLowerCase(Locale.ROOT))
        .anyMatch(value -> value.equals("pilot") || value.equals("prod") || value.equals("production"));
    if (production && (adapter instanceof DeterministicFakeInventorySearchAdapter || adapter.provider().synthetic()))
      throw new IllegalStateException("Development inventory adapter cannot serve production: "
          + org.springframework.aop.support.AopUtils.getTargetClass(adapter).getSimpleName());
  }
}

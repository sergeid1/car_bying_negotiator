package com.dealanalyzer.purchase;

import java.util.Locale;
import java.util.stream.Stream;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public final class CheckoutProductionGuard implements SmartInitializingSingleton {
  private final Environment environment;
  private final CheckoutProvider checkout;
  public CheckoutProductionGuard(Environment environment, CheckoutProvider checkout) {
    this.environment = environment; this.checkout = checkout;
  }
  public void afterSingletonsInstantiated() {
    boolean production = Stream.concat(Stream.of(environment.getActiveProfiles()),
        Stream.of(environment.getProperty("checkout.deployment", "development")))
        .map(value -> value.toLowerCase(Locale.ROOT))
        .anyMatch(value -> value.equals("production") || value.equals("prod") || value.equals("pilot"));
    if (production && checkout instanceof DevelopmentNoChargeCheckout)
      throw new IllegalStateException("Development checkout adapter cannot serve production: DevelopmentNoChargeCheckout");
  }
}

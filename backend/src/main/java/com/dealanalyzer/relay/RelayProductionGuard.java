package com.dealanalyzer.relay;

import java.util.Locale;
import java.util.stream.Stream;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** The fake relay must never reach a dealer through a profile change. */
@Component
class RelayProductionGuard implements SmartInitializingSingleton {
  private final Environment environment;
  private final DealerMailRelay relay;

  RelayProductionGuard(Environment environment, DealerMailRelay relay) {
    this.environment = environment; this.relay = relay;
  }

  @Override public void afterSingletonsInstantiated() {
    boolean production = Stream.concat(Stream.of(environment.getActiveProfiles()),
            Stream.of(environment.getProperty("evidence.deployment", "development")))
        .map(value -> value.toLowerCase(Locale.ROOT))
        .anyMatch(value -> value.equals("production") || value.equals("prod") || value.equals("pilot"));
    if (production && relay instanceof FakeDealerMailRelay)
      throw new IllegalStateException("The fake dealer email relay cannot serve production; set dealer-relay.provider");
  }
}

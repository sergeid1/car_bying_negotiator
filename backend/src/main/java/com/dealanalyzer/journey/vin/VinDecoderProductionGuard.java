package com.dealanalyzer.journey.vin;

import java.util.Locale;
import java.util.stream.Stream;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** The fake decoder's sample data must never be shown to a pilot Buyer as a decoded VIN. */
@Component
class VinDecoderProductionGuard implements SmartInitializingSingleton {
  private final Environment environment;
  private final VinDecoder decoder;

  VinDecoderProductionGuard(Environment environment, VinDecoder decoder) {
    this.environment = environment; this.decoder = decoder;
  }

  @Override public void afterSingletonsInstantiated() {
    boolean production = Stream.concat(Stream.of(environment.getActiveProfiles()),
            Stream.of(environment.getProperty("evidence.deployment", "development")))
        .map(value -> value.toLowerCase(Locale.ROOT))
        .anyMatch(value -> value.equals("production") || value.equals("prod") || value.equals("pilot"));
    if (production && decoder instanceof FakeVinDecoder)
      throw new IllegalStateException("The fake VIN decoder cannot serve production; set vin-decoder.provider");
  }
}

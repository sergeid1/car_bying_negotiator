package com.dealanalyzer.journey.vin;

import com.dealanalyzer.deployment.DeploymentStage;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** The fake decoder's sample data must never be shown to a pilot Buyer as a decoded VIN. */
@Component
class VinDecoderProductionGuard implements SmartInitializingSingleton {
  private final DeploymentStage stage;
  private final VinDecoder decoder;

  VinDecoderProductionGuard(Environment environment, VinDecoder decoder) {
    this.stage = DeploymentStage.from(environment); this.decoder = decoder;
  }

  @Override public void afterSingletonsInstantiated() {
    if (stage.productionLike() && decoder instanceof FakeVinDecoder)
      throw new IllegalStateException("The fake VIN decoder cannot serve production; set vin-decoder.provider");
  }
}

package com.dealanalyzer.vehicle.safety;

import com.dealanalyzer.deployment.DeploymentStage;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class VehicleSafetyProductionGuard implements SmartInitializingSingleton {
  private final DeploymentStage stage;
  private final VehicleSafetyProvider provider;
  public VehicleSafetyProductionGuard(Environment environment, VehicleSafetyProvider provider) {
    stage = DeploymentStage.from(environment); this.provider = provider;
  }
  @Override public void afterSingletonsInstantiated() {
    if (stage.productionLike() && provider instanceof FakeVehicleSafetyProvider)
      throw new IllegalStateException("The fake vehicle safety provider cannot serve pilot or production; set vehicle-safety.provider=nhtsa");
  }
}

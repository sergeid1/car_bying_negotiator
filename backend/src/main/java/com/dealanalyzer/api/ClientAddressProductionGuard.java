package com.dealanalyzer.api;

import com.dealanalyzer.deployment.DeploymentStage;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Behind the Worker and the pilot proxy, a live deployment without the origin secret would key every visitor on the
 * proxy's address and silently share one quota bucket (ticket 136), so it refuses to start instead.
 */
@Component
public class ClientAddressProductionGuard {
  public ClientAddressProductionGuard(Environment environment) {
    if (DeploymentStage.from(environment).productionLike()
        && environment.getProperty("security.client-address.origin-secret", "").isEmpty())
      throw new IllegalStateException(
          "Pilot/production requires security.client-address.origin-secret (ORIGIN_SECRET) for per-visitor quotas.");
  }
}

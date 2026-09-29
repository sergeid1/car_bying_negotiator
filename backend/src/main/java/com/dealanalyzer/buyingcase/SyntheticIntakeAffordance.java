package com.dealanalyzer.buyingcase;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Synthetic case intake is a development affordance. It follows the same shape as the document
 * parser mode: available by default for local work, and refused at startup under the pilot profile
 * so a release cannot expose a fixture-backed path to Buyers. When it is switched off the route is
 * never registered, so the path does not exist rather than answering differently.
 */
@Component
public class SyntheticIntakeAffordance {
  private final boolean enabled;

  SyntheticIntakeAffordance(Environment environment) {
    this.enabled = environment.getProperty("intake.synthetic.enabled", Boolean.class, Boolean.TRUE);
    if (enabled && environment.matchesProfiles("pilot")) {
      throw new IllegalStateException(
          "The pilot must not expose synthetic case intake; set intake.synthetic.enabled=false");
    }
  }

  public boolean enabled() {
    return enabled;
  }

  /** Guards against the route being wired up while the affordance is off. */
  public void requireEnabled() {
    if (!enabled) {
      throw new IllegalStateException("Synthetic case intake is disabled");
    }
  }
}

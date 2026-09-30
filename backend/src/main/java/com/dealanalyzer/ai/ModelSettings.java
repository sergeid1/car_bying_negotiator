package com.dealanalyzer.ai;

import java.util.Objects;
import org.springframework.core.env.Environment;

/**
 * Model configuration. None of it is secret: the vendor credential is read by the SDK from the
 * environment and never passes through Spring properties, source, or this record.
 *
 * @param enabled whether any model-backed path may run; off by default, and the rollout gate
 *     refuses to start with it on while an obligation is unmet
 */
public record ModelSettings(
    boolean enabled,
    String modelId,
    String thresholdSetVersion,
    String evaluationSetVersion) {
  public static final String DEFAULT_MODEL = "claude-opus-5";
  /** No frozen evaluation corpus exists yet (ticket 28), so no output can claim one. */
  public static final String NO_EVALUATION_SET = "none";

  public ModelSettings {
    Objects.requireNonNull(modelId);
    Objects.requireNonNull(thresholdSetVersion);
    Objects.requireNonNull(evaluationSetVersion);
  }

  public static ModelSettings from(Environment environment) {
    return new ModelSettings(
        environment.getProperty("ai.model.enabled", Boolean.class, Boolean.FALSE),
        environment.getProperty("ai.model.id", DEFAULT_MODEL),
        environment.getProperty("ai.model.threshold-set", "pilot-parameters.v1"),
        environment.getProperty("ai.model.evaluation-set", NO_EVALUATION_SET));
  }
}

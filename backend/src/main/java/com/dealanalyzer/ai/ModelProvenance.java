package com.dealanalyzer.ai;

import java.util.Objects;

/**
 * The versions that together identify how a model output was produced. Every material model output
 * carries one, and the rollout gate requires a recorded shadow comparison for exactly this tuple.
 */
public record ModelProvenance(
    String modelId,
    String promptVersion,
    String schemaVersion,
    String thresholdSetVersion,
    String evaluationSetVersion) {
  public ModelProvenance {
    for (String value : new String[] {modelId, promptVersion, schemaVersion, thresholdSetVersion, evaluationSetVersion}) {
      if (Objects.requireNonNull(value, "Model provenance is incomplete").isBlank() || value.length() > 100) {
        throw new IllegalArgumentException("Model provenance values must be 1-100 characters");
      }
    }
  }
}

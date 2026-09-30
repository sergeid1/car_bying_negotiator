package com.dealanalyzer.ai;

import java.util.Objects;

/**
 * A complete, schema-validated model output and the versions that produced it. The two cannot be
 * separated: a caller persisting the value has the provenance to persist alongside it.
 */
public record ModelResult<T>(T value, ModelProvenance provenance) {
  public ModelResult {
    Objects.requireNonNull(value, "A model result is never partial");
    Objects.requireNonNull(provenance);
  }
}

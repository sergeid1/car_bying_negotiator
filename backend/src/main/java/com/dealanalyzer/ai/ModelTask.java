package com.dealanalyzer.ai;

import com.dealanalyzer.operations.ProviderMeter;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * One code-owned model operation. The instructions, output schema and parser are fixed at build time
 * and versioned; a request cannot supply or alter any of them. A new instructions or schema text
 * needs a new version, and a new version needs its own recorded shadow comparison before rollout.
 *
 * @param parse turns schema-valid JSON into the result, throwing on anything it cannot accept. It
 *     must not repair or guess values: a rejected output fails the call closed.
 */
public record ModelTask<T>(
    ProviderMeter.Operation operation,
    String promptVersion,
    String schemaVersion,
    String instructions,
    Map<String, Object> outputSchema,
    int maxOutputTokens,
    Function<JsonNode, T> parse,
    boolean cacheInstructions) {
  public ModelTask(ProviderMeter.Operation operation, String promptVersion, String schemaVersion,
      String instructions, Map<String, Object> outputSchema, int maxOutputTokens,
      Function<JsonNode, T> parse) {
    this(operation, promptVersion, schemaVersion, instructions, outputSchema, maxOutputTokens, parse, false);
  }

  public ModelTask {
    Objects.requireNonNull(operation);
    Objects.requireNonNull(parse);
    if (promptVersion == null || !promptVersion.matches("[a-z0-9.-]{1,100}")
        || schemaVersion == null || !schemaVersion.matches("[a-z0-9.-]{1,100}")) {
      throw new IllegalArgumentException("Task versions are short code-owned identifiers");
    }
    if (instructions == null || instructions.isBlank()) throw new IllegalArgumentException("A task needs instructions");
    if (outputSchema == null || !"object".equals(outputSchema.get("type"))
        || !Boolean.FALSE.equals(outputSchema.get("additionalProperties"))) {
      throw new IllegalArgumentException("A task output schema must be a closed JSON object");
    }
    if (maxOutputTokens < 1 || maxOutputTokens > 16_000) {
      throw new IllegalArgumentException("Non-streaming output is bounded to 16000 tokens");
    }
    outputSchema = Map.copyOf(outputSchema);
  }
}

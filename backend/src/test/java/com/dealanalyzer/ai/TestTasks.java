package com.dealanalyzer.ai;

import com.dealanalyzer.operations.ProviderMeter;
import java.util.List;
import java.util.Map;

/** A small extraction-shaped task standing in for the real ones tickets 36 and 37 will register. */
public final class TestTasks {
  public record Total(Long totalMinor, String currency) {}

  public static final String INSTRUCTIONS =
      "Read the dealer document and report its out-the-door total in minor units, or null if it is not legible.";

  private TestTasks() {}

  public static ModelTask<Total> total() {
    return total("total-prompt.v1");
  }

  public static ModelTask<Total> total(String promptVersion) {
    Map<String, Object> schema = Map.of(
        "type", "object",
        "additionalProperties", false,
        "required", List.of("total_minor", "currency"),
        "properties", Map.of(
            "total_minor", Map.of("type", List.of("integer", "null")),
            "currency", Map.of("type", "string", "enum", List.of("USD"))));
    return new ModelTask<>(ProviderMeter.Operation.EXTRACTION, promptVersion, "total-schema.v1",
        INSTRUCTIONS, schema, 1024, node -> new Total(
            node.get("total_minor").isNull() ? null : node.get("total_minor").longValue(),
            node.get("currency").asText()));
  }
}

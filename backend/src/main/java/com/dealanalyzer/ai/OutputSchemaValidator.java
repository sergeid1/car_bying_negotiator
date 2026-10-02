package com.dealanalyzer.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Checks model output against the task's own schema before the task parser sees it. Structured output
 * constrains decoding, but the result is still vendor data: anything outside the schema is rejected,
 * never trimmed or repaired. Supports the subset task schemas use: type (including a list of types),
 * properties, required, additionalProperties false, items, and enum.
 */
final class OutputSchemaValidator {
  private OutputSchemaValidator() {}

  static void validate(Map<?, ?> schema, JsonNode value) {
    check(schema, value, 0);
  }

  private static void check(Map<?, ?> schema, JsonNode value, int depth) {
    if (depth > 32) throw new IllegalArgumentException("Output nesting exceeds the schema");
    Object type = schema.get("type");
    if (type != null && !matchesType(type, value)) throw new IllegalArgumentException("Output type mismatch");
    if (schema.get("enum") instanceof Collection<?> allowed
        && allowed.stream().noneMatch(option -> matchesConstant(option, value))) {
      throw new IllegalArgumentException("Output value outside its enumeration");
    }
    if (value.isObject()) {
      Map<?, ?> properties = schema.get("properties") instanceof Map<?, ?> declared ? declared : Map.of();
      if (schema.get("required") instanceof Collection<?> required) {
        for (Object name : required) {
          if (!value.has(String.valueOf(name))) throw new IllegalArgumentException("Output misses a required field");
        }
      }
      for (Iterator<Map.Entry<String, JsonNode>> fields = value.fields(); fields.hasNext(); ) {
        var field = fields.next();
        if (properties.get(field.getKey()) instanceof Map<?, ?> nested) check(nested, field.getValue(), depth + 1);
        else if (!Boolean.TRUE.equals(schema.get("additionalProperties"))) {
          throw new IllegalArgumentException("Output has a field the schema does not declare");
        }
      }
    }
    if (value.isArray() && schema.get("items") instanceof Map<?, ?> items) {
      for (JsonNode element : value) check(items, element, depth + 1);
    }
  }

  private static boolean matchesType(Object type, JsonNode value) {
    List<?> types = type instanceof Collection<?> many ? List.copyOf(many) : List.of(type);
    return types.stream().anyMatch(candidate -> switch (String.valueOf(candidate)) {
      case "object" -> value.isObject();
      case "array" -> value.isArray();
      case "string" -> value.isTextual();
      case "integer" -> value.isIntegralNumber();
      case "number" -> value.isNumber();
      case "boolean" -> value.isBoolean();
      case "null" -> value.isNull();
      default -> false;
    });
  }

  private static boolean matchesConstant(Object option, JsonNode value) {
    if (option == null) return value.isNull();
    if (option instanceof String text) return value.isTextual() && value.asText().equals(text);
    if (option instanceof Number number) return value.isNumber() && value.decimalValue().compareTo(new java.math.BigDecimal(number.toString())) == 0;
    if (option instanceof Boolean flag) return value.isBoolean() && value.asBoolean() == flag;
    return false;
  }
}

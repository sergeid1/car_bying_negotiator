package com.dealanalyzer.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;

/** Compares report replays while honoring only differences declared by the report contract. */
public final class ReportReplayEvaluator {
  public void assertReproduces(JsonNode original, JsonNode replay) {
    requireEqual(
        "frozen input fingerprint",
        original.at("/reproduction/inputFingerprint"),
        replay.at("/reproduction/inputFingerprint"));
    requireEqual(
        "policy versions",
        original.at("/analysis/policyVersions"),
        replay.at("/analysis/policyVersions"));

    JsonNode originalDifferences = original.at("/reproduction/expectedVersionDifferences");
    JsonNode replayDifferences = replay.at("/reproduction/expectedVersionDifferences");
    requireEqual("declared replay differences", originalDifferences, replayDifferences);
    if (!originalDifferences.isObject()) {
      throw new IllegalStateException("Report replay differences must be declared as an object");
    }

    ObjectNode normalizedOriginal = original.deepCopy();
    ObjectNode normalizedReplay = replay.deepCopy();
    List<String> differenceFields = new ArrayList<>();
    originalDifferences.fieldNames().forEachRemaining(differenceFields::add);
    differenceFields.forEach(field -> {
      normalizedOriginal.remove(field);
      normalizedReplay.remove(field);
    });
    requireEqual("structured report replay", normalizedOriginal, normalizedReplay);
  }

  private static void requireEqual(String subject, JsonNode original, JsonNode replay) {
    if (!original.equals(replay)) {
      throw new IllegalStateException(subject + " changed during report replay");
    }
  }
}

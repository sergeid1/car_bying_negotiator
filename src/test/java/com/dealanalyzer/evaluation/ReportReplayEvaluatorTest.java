package com.dealanalyzer.evaluation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class ReportReplayEvaluatorTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void reportCannotDeclareArbitraryFieldsAsExpectedReplayDifferences() throws Exception {
    ObjectNode original = report(1);
    ObjectNode replay = report(2);
    ((ObjectNode) original.at("/reproduction/expectedVersionDifferences"))
        .put("coverage", "provider result changed");
    ((ObjectNode) replay.at("/reproduction/expectedVersionDifferences"))
        .put("coverage", "provider result changed");

    assertThatThrownBy(() -> new ReportReplayEvaluator().assertReproduces(original, replay))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unsupported replay difference")
        .hasMessageContaining("coverage");
  }

  private static ObjectNode report(int version) throws Exception {
    return (ObjectNode) JSON.readTree("""
        {
          "id": "report-%d",
          "version": %d,
          "generatedAt": "2026-08-27T16:00:0%dZ",
          "analysis": { "policyVersions": { "report": "v1" } },
          "coverage": { "market": "available" },
          "reproduction": {
            "inputFingerprint": "frozen-input",
            "expectedVersionDifferences": {
              "id": "new identity",
              "version": "incremented version",
              "generatedAt": "new generation time"
            }
          }
        }
        """.formatted(version, version, version));
  }
}

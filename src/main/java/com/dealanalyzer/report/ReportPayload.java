package com.dealanalyzer.report;

import java.time.Instant;
import java.util.Map;

public record ReportPayload(
    String id,
    String caseId,
    String schemaVersion,
    int version,
    Instant generatedAt,
    DealAnalysis analysis,
    Reproduction reproduction,
    Map<String, String> coverage) {

  public record Reproduction(
      String inputFingerprint,
      String analysisFingerprint,
      Map<String, String> expectedVersionDifferences) {}
}


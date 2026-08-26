package com.dealanalyzer.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

@Component
public final class ReportAssembler {
  private final ObjectMapper json;
  private final Clock clock;

  @Autowired
  public ReportAssembler(ObjectMapper json) {
    this(json, Clock.systemUTC());
  }

  ReportAssembler(ObjectMapper json, Clock clock) {
    this.json = json.copy().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);
    this.clock = clock;
  }

  public AssembledReport assemble(
      String caseId, int version, String inputFingerprint, DealAnalysis analysis) {
    String analysisFingerprint = sha256(write(analysis));
    Instant generatedAt = clock.instant();
    ReportPayload payload = new ReportPayload(
        UUID.randomUUID().toString(),
        caseId,
        "deal-analysis-report.v1",
        version,
        generatedAt,
        analysis,
        new ReportPayload.Reproduction(
            inputFingerprint,
            analysisFingerprint,
            Map.of(
                "id", "new immutable report identity",
                "version", "incremented report version",
                "generatedAt", "new generation timestamp")),
        Map.of(
            "jurisdiction", "Synthetic jurisdiction fixture",
            "asOf", analysis.marketAssessment().dataAsOf().toString()));
    return new AssembledReport(write(payload), analysisFingerprint, generatedAt);
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("Unable to serialize structured report", exception);
    }
  }

  private static String sha256(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  public record AssembledReport(String payloadJson, String analysisFingerprint, Instant generatedAt) {}
}

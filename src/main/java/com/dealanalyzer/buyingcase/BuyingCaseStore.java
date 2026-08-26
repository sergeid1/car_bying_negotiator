package com.dealanalyzer.buyingcase;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class BuyingCaseStore {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public BuyingCaseStore(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Transactional
  public void createCase(
      String caseId,
      String operationId,
      SyntheticAnalysisRequest input,
      String inputJson,
      String inputFingerprint,
      Instant now) {
    jdbc.update(
        """
        INSERT INTO analysis_cases
          (id, buyer_zip, dealer_zip, purchase_method, status, vehicle_json,
           dealer_offer_json, input_json, input_fingerprint, created_at, updated_at)
        VALUES (?, ?, ?, 'CASH', 'ANALYZING', ?, ?, ?, ?, ?, ?)
        """,
        caseId,
        input.buyerZip(),
        input.dealerZip(),
        write(input.vehicle()),
        write(input.dealerOffer()),
        inputJson,
        inputFingerprint,
        databaseTimestamp(now),
        databaseTimestamp(now));
    insertOperation(operationId, caseId, now);
  }

  @Transactional
  public void createOperation(String operationId, String caseId, Instant now) {
    jdbc.update(
        "UPDATE analysis_cases SET status = 'ANALYZING', updated_at = ? WHERE id = ?",
        databaseTimestamp(now),
        caseId);
    if (jdbc.queryForObject("SELECT COUNT(*) FROM analysis_cases WHERE id = ?", Integer.class, caseId)
        == 0) {
      throw new ResourceNotFoundException("Buying Case not found");
    }
    insertOperation(operationId, caseId, now);
  }

  private void insertOperation(String operationId, String caseId, Instant now) {
    jdbc.update(
        """
        INSERT INTO operations (id, case_id, stage, created_at, updated_at)
        VALUES (?, ?, 'QUEUED', ?, ?)
        """,
        operationId,
        caseId,
        databaseTimestamp(now),
        databaseTimestamp(now));
  }

  public StoredCase requireCase(String caseId) {
    List<StoredCase> matches =
        jdbc.query(
            "SELECT * FROM analysis_cases WHERE id = ?",
            (resultSet, rowNumber) -> mapCase(resultSet),
            caseId);
    return matches.stream()
        .findFirst()
        .orElseThrow(() -> new ResourceNotFoundException("Buying Case not found"));
  }

  public OperationView requireOperation(String operationId) {
    return jdbc.query(
            "SELECT id, case_id, stage, error_message, created_at, updated_at FROM operations WHERE id = ?",
            (resultSet, rowNumber) ->
                new OperationView(
                    resultSet.getString("id"),
                    resultSet.getString("case_id"),
                    resultSet.getString("stage"),
                    resultSet.getString("error_message"),
                    readTimestamp(resultSet, "created_at"),
                    readTimestamp(resultSet, "updated_at")),
            operationId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ResourceNotFoundException("Operation not found"));
  }

  public void updateStage(String operationId, String stage, Instant now) {
    jdbc.update(
        "UPDATE operations SET stage = ?, updated_at = ? WHERE id = ?",
        stage,
        databaseTimestamp(now),
        operationId);
  }

  @Transactional
  public void complete(
      String caseId,
      String operationId,
      int version,
      String payloadJson,
      String analysisFingerprint,
      Instant now) {
    jdbc.update(
        """
        INSERT INTO analysis_reports
          (case_id, report_version, payload_json, analysis_fingerprint, created_at)
        VALUES (?, ?, ?, ?, ?)
        """,
        caseId,
        version,
        payloadJson,
        analysisFingerprint,
        databaseTimestamp(now));
    jdbc.update(
        """
        UPDATE analysis_cases
        SET status = 'COMPLETE', current_report_version = ?, updated_at = ?
        WHERE id = ?
        """,
        version,
        databaseTimestamp(now),
        caseId);
    updateStage(operationId, "COMPLETE", now);
  }

  @Transactional
  public void fail(String caseId, String operationId, String safeMessage, Instant now) {
    jdbc.update(
        "UPDATE analysis_cases SET status = 'FAILED', updated_at = ? WHERE id = ?",
        databaseTimestamp(now),
        caseId);
    jdbc.update(
        """
        UPDATE operations SET stage = 'FAILED', error_message = ?, updated_at = ? WHERE id = ?
        """,
        safeMessage,
        databaseTimestamp(now),
        operationId);
  }

  public JsonNode requireReport(String caseId, Optional<Integer> requestedVersion) {
    StoredCase buyingCase = requireCase(caseId);
    int version = requestedVersion.orElse(buyingCase.currentReportVersion());
    if (version == 0) {
      throw new ResourceNotFoundException("Deal Analysis Report not available");
    }
    return jdbc.query(
            "SELECT payload_json FROM analysis_reports WHERE case_id = ? AND report_version = ?",
            (resultSet, rowNumber) -> readTree(resultSet.getString("payload_json")),
            caseId,
            version)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ResourceNotFoundException("Deal Analysis Report not found"));
  }

  private StoredCase mapCase(ResultSet resultSet) throws SQLException {
    return new StoredCase(
        resultSet.getString("id"),
        resultSet.getString("buyer_zip"),
        resultSet.getString("dealer_zip"),
        resultSet.getString("purchase_method"),
        resultSet.getString("status"),
        readTree(resultSet.getString("vehicle_json")),
        readTree(resultSet.getString("dealer_offer_json")),
        resultSet.getString("input_json"),
        resultSet.getString("input_fingerprint"),
        resultSet.getInt("current_report_version"),
        readTimestamp(resultSet, "created_at"),
        readTimestamp(resultSet, "updated_at"));
  }

  private static OffsetDateTime databaseTimestamp(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }

  private static Instant readTimestamp(ResultSet resultSet, String column) throws SQLException {
    return resultSet.getObject(column, OffsetDateTime.class).toInstant();
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("Unable to serialize structured input", exception);
    }
  }

  private JsonNode readTree(String value) {
    try {
      return json.readTree(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Stored structured data is invalid", exception);
    }
  }

  public record StoredCase(
      String id,
      String buyerZip,
      String dealerZip,
      String purchaseMethod,
      String status,
      JsonNode vehicle,
      JsonNode dealerOffer,
      String inputJson,
      String inputFingerprint,
      int currentReportVersion,
      Instant createdAt,
      Instant updatedAt) {}

  public record OperationView(
      String id,
      String caseId,
      String stage,
      String error,
      Instant createdAt,
      Instant updatedAt) {}
}

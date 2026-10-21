package com.dealanalyzer.buyingcase.persistence;

import com.dealanalyzer.buyingcase.EphemeralBuyingCaseService;
import com.dealanalyzer.buyingcase.ResourceNotFoundException;
import com.dealanalyzer.explanation.ReportExplainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** An immutable translation per report, independent of the existing English explanation. */
@Service
public class SpanishReportExplanationService {
  private final JdbcTemplate jdbc;
  private final EphemeralBuyingCaseService access;
  private final ReportExplainer explainer;
  private final ObjectMapper json;
  private final TransactionTemplate transaction;
  SpanishReportExplanationService(JdbcTemplate jdbc, EphemeralBuyingCaseService access, ReportExplainer explainer,
      ObjectMapper json, PlatformTransactionManager manager) {
    this.jdbc = jdbc; this.access = access; this.explainer = explainer; this.json = json;
    this.transaction = new TransactionTemplate(manager);
  }
  public JsonNode explain(String caseId, int version, String token) {
    access.reopen(caseId, token);
    var stored = stored(caseId, version);
    if (stored != null) return read(stored);
    var reports = jdbc.queryForList("select payload_json from analysis_reports where case_id = ? and report_version = ?",
        String.class, caseId, version);
    if (reports.isEmpty()) throw new ResourceNotFoundException("Report not found");
    String candidate;
    try { candidate = json.writeValueAsString(explainer.explain(caseId, read(reports.getFirst()), "es")); }
    catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    String served = transaction.execute(status -> {
      var locked = jdbc.queryForList("select id from analysis_cases where id = ? for update", String.class, caseId);
      if (locked.isEmpty()) throw new ResourceNotFoundException("Buying Case not found");
      access.reopen(caseId, token);
      String existing = stored(caseId, version);
      if (existing != null) return existing;
      jdbc.update("insert into spanish_report_explanations(case_id, report_version, payload_json, created_at) values (?, ?, ?, ?)",
          caseId, version, candidate, Timestamp.from(Instant.now()));
      return candidate;
    });
    return read(served);
  }
  private String stored(String caseId, int version) {
    return jdbc.queryForList("select payload_json from spanish_report_explanations where case_id = ? and report_version = ?",
        String.class, caseId, version).stream().findFirst().orElse(null);
  }
  private JsonNode read(String text) {
    try { return json.readTree(text); }
    catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
  }
}

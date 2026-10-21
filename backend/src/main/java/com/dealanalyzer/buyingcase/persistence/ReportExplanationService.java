package com.dealanalyzer.buyingcase.persistence;

import com.dealanalyzer.buyingcase.EphemeralBuyingCaseService;
import com.dealanalyzer.buyingcase.ResourceNotFoundException;
import com.dealanalyzer.explanation.ExplanationFacts;
import com.dealanalyzer.explanation.ReportExplainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The explanation of one finalized report, produced once and served unchanged afterwards. The first
 * read produces it outside any transaction (so no transaction stays open during a model call), then
 * stores it in its own short transaction under the case lock. A concurrent first read that loses the
 * race serves the stored row instead of its own, so every reader sees one text per report version.
 * The report itself is only read.
 */
@Service
public class ReportExplanationService {
  private final AnalysisReportRepository reports;
  private final ReportExplanationRepository explanations;
  private final AnalysisCaseRepository cases;
  private final EphemeralBuyingCaseService access;
  private final ObjectMapper json;
  private final ReportExplainer explainer;
  private final TransactionTemplate transaction;

  public ReportExplanationService(AnalysisReportRepository reports, ReportExplanationRepository explanations,
      AnalysisCaseRepository cases, EphemeralBuyingCaseService access, ObjectMapper json, ReportExplainer explainer,
      PlatformTransactionManager transactions) {
    this.reports = reports; this.explanations = explanations; this.cases = cases; this.access = access;
    this.json = json; this.explainer = explainer; this.transaction = new TransactionTemplate(transactions);
  }

  public JsonNode explain(String caseId, int reportVersion, String token) {
    access.reopen(caseId, token);
    var key = new AnalysisReportKey(caseId, reportVersion);
    var stored = explanations.findProjectedById(key);
    if (stored.isPresent()) return read(stored.get().getPayloadJson());
    JsonNode report = read(reports.findProjectedById(key)
        .orElseThrow(() -> new ResourceNotFoundException("Report not found")).getPayloadJson());
    if (!ExplanationFacts.supports(report))
      throw new ResourceNotFoundException("No explanation exists for this report");
    var explanation = explainer.explain(caseId, report);
    String payload;
    try { payload = json.writeValueAsString(explanation); }
    catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    String served = transaction.execute(status -> {
      // The case lock orders concurrent first reads and fences deletion, which takes the same lock.
      cases.findLockedById(caseId).orElseThrow(() -> new ResourceNotFoundException("Buying Case not found"));
      access.reopen(caseId, token);
      var existing = explanations.findProjectedById(key);
      if (existing.isPresent()) return existing.get().getPayloadJson();
      explanations.saveAndFlush(new ReportExplanationEntity(key, explanation, payload, Instant.now()));
      return payload;
    });
    return read(served);
  }

  private JsonNode read(String payload) {
    try { return json.readTree(payload); }
    catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
  }
}

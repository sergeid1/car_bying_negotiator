package com.dealanalyzer.buyingcase.persistence;

import com.dealanalyzer.buyingcase.EphemeralBuyingCaseService;
import com.dealanalyzer.buyingcase.ResourceNotFoundException;
import com.dealanalyzer.explanation.ExplanationFacts;
import com.dealanalyzer.explanation.ReportExplainer;
import com.dealanalyzer.explanation.ReportExplanation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/**
 * The explanation of one finalized report. Nothing is written: the report is read and the checked
 * explanation is returned. Deliberately not transactional, so no database transaction stays open
 * during a model call.
 */
@Service
public class ReportExplanationService {
  private final AnalysisReportRepository reports;
  private final EphemeralBuyingCaseService access;
  private final ObjectMapper json;
  private final ReportExplainer explainer;

  public ReportExplanationService(AnalysisReportRepository reports, EphemeralBuyingCaseService access,
      ObjectMapper json, ReportExplainer explainer) {
    this.reports = reports; this.access = access; this.json = json; this.explainer = explainer;
  }

  public ReportExplanation explain(String caseId, int reportVersion, String token) {
    access.reopen(caseId, token);
    JsonNode report;
    try {
      report = json.readTree(reports.findProjectedById(new AnalysisReportKey(caseId, reportVersion))
          .orElseThrow(() -> new ResourceNotFoundException("Report not found")).getPayloadJson());
    } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    if (!ExplanationFacts.CORE_SCHEMA.equals(report.path("schemaVersion").asText()))
      throw new ResourceNotFoundException("No explanation exists for this report");
    return explainer.explain(caseId, report);
  }
}

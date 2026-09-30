package com.dealanalyzer.buyingcase.persistence;

import com.dealanalyzer.buyingcase.EphemeralBuyingCaseService;
import com.dealanalyzer.buyingcase.ResourceNotFoundException;
import com.dealanalyzer.negotiation.GroundedLanguage;
import com.dealanalyzer.negotiation.ReplyAssistant;
import com.dealanalyzer.negotiation.ReplyDraft;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NegotiationService {
  private final AnalysisReportRepository reports;
  private final NegotiationVersionRepository versions;
  private final EphemeralBuyingCaseService access;
  private final ObjectMapper json;
  private final ReplyAssistant replies;
  public NegotiationService(AnalysisReportRepository reports, NegotiationVersionRepository versions,
      EphemeralBuyingCaseService access, ObjectMapper json, ReplyAssistant replies) {
    this.reports = reports; this.versions = versions; this.access = access; this.json = json;
    this.replies = replies;
  }

  // Called only after the source report insert, in the same transaction.
  void append(String caseId, int reportVersion) {
    var id = new AnalysisReportKey(caseId, reportVersion);
    var source = reports.findProjectedById(id).orElseThrow();
    JsonNode report = read(source.getPayloadJson());
    var policy = new GroundedLanguage();
    var language = policy.generate(report);
    policy.validate(report, language);
    var payload = json.createObjectNode();
    payload.put("caseId", caseId);
    payload.put("reportVersion", reportVersion);
    payload.put("generatedAt", report.path("generatedAt").asText());
    payload.put("sourceReport", "/api/v1/cases/" + caseId + "/reports/" + reportVersion);
    payload.set("language", json.valueToTree(language));
    versions.saveAndFlush(new NegotiationVersionEntity(id, payload.toString()));
  }

  @Transactional(readOnly = true)
  public JsonNode get(String caseId, int reportVersion, String token) {
    access.reopen(caseId, token);
    return read(versions.findProjectedById(new AnalysisReportKey(caseId, reportVersion))
        .orElseThrow(() -> new ResourceNotFoundException("Negotiation version not found")).getPayloadJson());
  }

  /**
   * A reply suggestion for one Dealer answer, grounded in the identified finalized report. Nothing is
   * written: the Dealer's answer and the suggestion are returned only. Deliberately not transactional,
   * so no database transaction stays open during a model call.
   */
  public ReplyDraft reply(String caseId, int reportVersion, String token, String dealerResponse) {
    access.reopen(caseId, token);
    JsonNode report = read(reports.findProjectedById(new AnalysisReportKey(caseId, reportVersion))
        .orElseThrow(() -> new ResourceNotFoundException("Report not found")).getPayloadJson());
    return replies.draft(caseId, report, dealerResponse);
  }

  private JsonNode read(String payload) {
    try { return json.readTree(payload); }
    catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
  }
}

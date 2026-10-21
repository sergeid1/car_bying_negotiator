package com.dealanalyzer.api;

import com.dealanalyzer.buyingcase.persistence.ReportExplanationService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The checked explanation of a finalized report, produced on first read and stored once per report
 * version. It changes no report, approval or state.
 */
@RestController
@RequestMapping("/api/v1/cases/{caseId}/reports/{version}/explanation")
public class ReportExplanationController {
  private final ReportExplanationService service;
  private final com.dealanalyzer.buyingcase.persistence.SpanishReportExplanationService spanish;
  public ReportExplanationController(ReportExplanationService service,
      com.dealanalyzer.buyingcase.persistence.SpanishReportExplanationService spanish) { this.service = service; this.spanish = spanish; }

  @GetMapping
  public JsonNode get(@PathVariable String caseId, @PathVariable int version,
      @RequestHeader("Case-Access-Token") String token,
      @org.springframework.web.bind.annotation.RequestParam(defaultValue = "en") String language) {
    return "es".equals(language) ? spanish.explain(caseId, version, token) : service.explain(caseId, version, token);
  }
}

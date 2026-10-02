package com.dealanalyzer.api;

import com.dealanalyzer.buyingcase.persistence.ReportExplanationService;
import com.dealanalyzer.explanation.ReportExplanation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The checked explanation of a finalized report. Read-only: it changes no report, approval or state. */
@RestController
@RequestMapping("/api/v1/cases/{caseId}/reports/{version}/explanation")
public class ReportExplanationController {
  private final ReportExplanationService service;
  public ReportExplanationController(ReportExplanationService service) { this.service = service; }

  @GetMapping
  public ReportExplanation get(@PathVariable String caseId, @PathVariable int version,
      @RequestHeader("Case-Access-Token") String token) {
    return service.explain(caseId, version, token);
  }
}

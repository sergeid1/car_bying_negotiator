package com.dealanalyzer.api;

import com.dealanalyzer.buyingcase.AnalysisWorkflow;
import com.dealanalyzer.buyingcase.AnalysisWorkflow.CaseView;
import com.dealanalyzer.buyingcase.AnalysisWorkflow.StartedAnalysis;
import com.dealanalyzer.buyingcase.BuyingCaseStore.OperationView;
import com.dealanalyzer.buyingcase.SyntheticAnalysisRequest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

@RestController
@RequestMapping("/api/v1")
@Validated
public class DealAnalysisController {
  private final AnalysisWorkflow workflow;

  public DealAnalysisController(AnalysisWorkflow workflow) {
    this.workflow = workflow;
  }

  @PostMapping("/cases/synthetic")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public StartedAnalysis start(
      @NotBlank @Size(max = 200) @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody SyntheticAnalysisRequest request) {
    return workflow.start(request, idempotencyKey);
  }

  @PostMapping("/cases/{caseId}/analyses")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public StartedAnalysis regenerate(
      @PathVariable String caseId,
      @NotBlank @Size(max = 200) @RequestHeader("Idempotency-Key") String idempotencyKey) {
    return workflow.regenerate(caseId, idempotencyKey);
  }

  @GetMapping("/cases/{caseId}")
  public CaseView getCase(@PathVariable String caseId) {
    return workflow.getCase(caseId);
  }

  @GetMapping("/operations/{operationId}")
  public OperationView getOperation(@PathVariable String operationId) {
    return workflow.getOperation(operationId);
  }

  @GetMapping("/cases/{caseId}/reports/latest")
  public JsonNode getLatestReport(@PathVariable String caseId) {
    return workflow.getReport(caseId, Optional.empty());
  }

  @GetMapping("/cases/{caseId}/reports/{version}")
  public JsonNode getReport(@PathVariable String caseId, @PathVariable int version) {
    return workflow.getReport(caseId, Optional.of(version));
  }
}

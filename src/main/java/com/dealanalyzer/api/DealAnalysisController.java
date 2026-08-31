package com.dealanalyzer.api;

import com.dealanalyzer.buyingcase.AnalysisWorkflow;
import com.dealanalyzer.buyingcase.CreateBuyingCaseRequest;
import com.dealanalyzer.buyingcase.ChangeConsentRequest;
import com.dealanalyzer.buyingcase.EphemeralBuyingCaseService;
import com.dealanalyzer.buyingcase.EphemeralBuyingCaseService.BuyingCaseView;
import com.dealanalyzer.buyingcase.EphemeralBuyingCaseService.CreatedBuyingCase;
import com.dealanalyzer.buyingcase.persistence.EphemeralCasePersistence.StoredConsent;
import com.dealanalyzer.buyingcase.AnalysisWorkflow.CaseView;
import com.dealanalyzer.buyingcase.AnalysisWorkflow.StartedAnalysis;
import com.dealanalyzer.buyingcase.persistence.BuyingCaseStore.OperationView;
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
  private final EphemeralBuyingCaseService ephemeralCases;

  public DealAnalysisController(
      AnalysisWorkflow workflow, EphemeralBuyingCaseService ephemeralCases) {
    this.workflow = workflow;
    this.ephemeralCases = ephemeralCases;
  }

  @PostMapping("/cases")
  @ResponseStatus(HttpStatus.CREATED)
  public CreatedBuyingCase createCase(
      @NotBlank @Size(max = 200) @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestHeader(value = "Case-Access-Token", required = false) String accessToken,
      @Valid @RequestBody CreateBuyingCaseRequest request) {
    return ephemeralCases.create(request, idempotencyKey, accessToken);
  }

  @GetMapping("/cases/{caseId}")
  public Object getCase(
      @PathVariable String caseId,
      @RequestHeader(value = "Case-Access-Token", required = false) String accessToken) {
    if (ephemeralCases.isEphemeralCase(caseId)) {
      return ephemeralCases.reopen(caseId, accessToken);
    }
    return workflow.getCase(caseId, accessToken);
  }

  @PostMapping("/cases/{caseId}/consents")
  public StoredConsent changeConsent(
      @PathVariable String caseId,
      @RequestHeader("Case-Access-Token") String accessToken,
      @Valid @RequestBody ChangeConsentRequest request) {
    return ephemeralCases.changeConsent(caseId, accessToken, request);
  }

  @PostMapping("/cases/synthetic")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public StartedAnalysis start(
      @NotBlank @Size(max = 200) @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestHeader(value = "Case-Access-Token", required = false) String accessToken,
      @Valid @RequestBody SyntheticAnalysisRequest request) {
    return workflow.start(request, idempotencyKey, accessToken);
  }

  @PostMapping("/cases/{caseId}/analyses")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public StartedAnalysis regenerate(
      @PathVariable String caseId,
      @NotBlank @Size(max = 200) @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestHeader("Case-Access-Token") String accessToken) {
    return workflow.regenerate(caseId, idempotencyKey, accessToken);
  }

  @GetMapping("/operations/{operationId}")
  public OperationView getOperation(
      @PathVariable String operationId,
      @RequestHeader("Case-Access-Token") String accessToken) {
    return workflow.getOperation(operationId, accessToken);
  }

  @GetMapping("/cases/{caseId}/reports/latest")
  public JsonNode getLatestReport(
      @PathVariable String caseId,
      @RequestHeader("Case-Access-Token") String accessToken) {
    return workflow.getReport(caseId, Optional.empty(), accessToken);
  }

  @GetMapping("/cases/{caseId}/reports/{version}")
  public JsonNode getReport(
      @PathVariable String caseId,
      @PathVariable int version,
      @RequestHeader("Case-Access-Token") String accessToken) {
    return workflow.getReport(caseId, Optional.of(version), accessToken);
  }
}

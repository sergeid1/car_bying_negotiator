package com.dealanalyzer.api;

import com.dealanalyzer.buyingcase.AnalysisWorkflow;
import com.dealanalyzer.buyingcase.AnalysisWorkflow.StartedAnalysis;
import com.dealanalyzer.buyingcase.ChangeConsentRequest;
import com.dealanalyzer.buyingcase.CreateBuyingCaseRequest;
import com.dealanalyzer.buyingcase.EphemeralBuyingCaseService;
import com.dealanalyzer.buyingcase.EphemeralBuyingCaseService.CreatedBuyingCase;
import com.dealanalyzer.buyingcase.SyntheticAnalysisRequest;
import com.dealanalyzer.buyingcase.persistence.BuyingCaseStore.OperationView;
import com.dealanalyzer.buyingcase.persistence.EphemeralCasePersistence.StoredConsent;
import com.dealanalyzer.vehicle.EstablishVehicleRequest;
import com.dealanalyzer.vehicle.ResolveIdentityConflictRequest;
import com.dealanalyzer.vehicle.VehicleEvidenceService;
import com.dealanalyzer.vehicle.VehicleEvidenceService.VehicleEvidenceView;
import com.dealanalyzer.document.DocumentIntakeService;
import com.dealanalyzer.document.DocumentIntakeService.StartedDocumentUpload;
import com.dealanalyzer.document.persistence.DocumentIntakePersistence.DocumentOperationView;
import com.dealanalyzer.document.persistence.DocumentIntakePersistence.SourceDocumentView;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
@Validated
public class DealAnalysisController {
  private final AnalysisWorkflow workflow;
  private final EphemeralBuyingCaseService ephemeralCases;
  private final VehicleEvidenceService vehicleEvidence;
  private final DocumentIntakeService documentIntake;

  public DealAnalysisController(
      AnalysisWorkflow workflow,
      EphemeralBuyingCaseService ephemeralCases,
      VehicleEvidenceService vehicleEvidence,
      DocumentIntakeService documentIntake) {
    this.workflow = workflow;
    this.ephemeralCases = ephemeralCases;
    this.vehicleEvidence = vehicleEvidence;
    this.documentIntake = documentIntake;
  }

  @PostMapping(value = "/cases/{caseId}/documents", consumes = "multipart/form-data")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public StartedDocumentUpload uploadDocument(
      @PathVariable String caseId,
      @NotBlank @Size(max = 200) @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestHeader("Case-Access-Token") String accessToken,
      @RequestPart("file") MultipartFile file) {
    return documentIntake.upload(caseId, accessToken, idempotencyKey, file);
  }

  @GetMapping("/cases/{caseId}/documents/{documentId}")
  public SourceDocumentView getDocument(
      @PathVariable String caseId,
      @PathVariable String documentId,
      @RequestHeader("Case-Access-Token") String accessToken) {
    return documentIntake.getDocument(caseId, documentId, accessToken);
  }

  @PostMapping("/operations/{operationId}/retries")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public DocumentOperationView retryDocumentOperation(
      @PathVariable String operationId,
      @NotBlank @Size(max = 200) @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestHeader("Case-Access-Token") String accessToken) {
    return documentIntake.retry(operationId, accessToken, idempotencyKey);
  }

  @PostMapping("/cases/{caseId}/vehicle-evidence")
  public VehicleEvidenceView establishVehicle(
      @PathVariable String caseId,
      @RequestHeader("Case-Access-Token") String accessToken,
      @RequestBody EstablishVehicleRequest request) {
    return vehicleEvidence.establish(caseId, accessToken, request);
  }

  @GetMapping("/cases/{caseId}/vehicle-evidence")
  public VehicleEvidenceView getVehicleEvidence(
      @PathVariable String caseId,
      @RequestHeader("Case-Access-Token") String accessToken) {
    return vehicleEvidence.get(caseId, accessToken);
  }

  @PostMapping("/cases/{caseId}/vehicle-evidence/conflicts/{conflictId}/resolution")
  public VehicleEvidenceView resolveVehicleConflict(
      @PathVariable String caseId,
      @PathVariable String conflictId,
      @RequestHeader("Case-Access-Token") String accessToken,
      @RequestBody ResolveIdentityConflictRequest request) {
    return vehicleEvidence.resolve(caseId, accessToken, conflictId, request);
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
  public Object getOperation(
      @PathVariable String operationId,
      @RequestHeader("Case-Access-Token") String accessToken) {
    Optional<DocumentOperationView> documentOperation =
        documentIntake.findOperation(operationId, accessToken);
    if (documentOperation.isPresent()) {
      return documentOperation.orElseThrow();
    }
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

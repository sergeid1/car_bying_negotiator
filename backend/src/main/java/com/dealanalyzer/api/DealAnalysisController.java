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
import com.dealanalyzer.extraction.DocumentExtractionService;
import com.dealanalyzer.extraction.persistence.DocumentExtractionPersistence.ExtractionSummary;
import com.dealanalyzer.extraction.persistence.DocumentExtractionPersistence.ExtractionView;
import com.dealanalyzer.verification.CorrectDealTermRequest;
import com.dealanalyzer.verification.DealerOfferVerificationService;
import com.dealanalyzer.verification.VerifyDealerOfferRequest;
import com.dealanalyzer.verification.persistence.DealerOfferPersistence.CorrectionView;
import com.dealanalyzer.verification.persistence.DealerOfferPersistence.DealerOfferView;
import com.dealanalyzer.economics.CashEconomicsService;
import com.dealanalyzer.economics.CalculateCashEconomicsRequest;
import com.dealanalyzer.economics.persistence.CashEconomicsPersistence.CashEconomicsView;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
@Validated
public class DealAnalysisController {
  private final AnalysisWorkflow workflow;
  private final EphemeralBuyingCaseService ephemeralCases;
  private final VehicleEvidenceService vehicleEvidence;
  private final DocumentIntakeService documentIntake;
  private final DocumentExtractionService documentExtractions;
  private final DealerOfferVerificationService dealerOfferVerification;
  private final CashEconomicsService cashEconomics;

  public DealAnalysisController(
      AnalysisWorkflow workflow,
      EphemeralBuyingCaseService ephemeralCases,
      VehicleEvidenceService vehicleEvidence,
      DocumentIntakeService documentIntake,
      DocumentExtractionService documentExtractions,
      DealerOfferVerificationService dealerOfferVerification,
      CashEconomicsService cashEconomics) {
    this.workflow = workflow;
    this.ephemeralCases = ephemeralCases;
    this.vehicleEvidence = vehicleEvidence;
    this.documentIntake = documentIntake;
    this.documentExtractions = documentExtractions;
    this.dealerOfferVerification = dealerOfferVerification;
    this.cashEconomics = cashEconomics;
  }

  @PostMapping("/cases/{caseId}/cash-economics")
  @ResponseStatus(HttpStatus.CREATED)
  public CashEconomicsView calculateCashEconomics(
      @PathVariable String caseId,
      @RequestHeader("Case-Access-Token") String accessToken,
      @RequestBody(required = false) CalculateCashEconomicsRequest request) {
    return cashEconomics.calculate(caseId, accessToken, request);
  }

  @GetMapping("/cases/{caseId}/cash-economics/latest")
  public CashEconomicsView getLatestCashEconomics(
      @PathVariable String caseId,
      @RequestHeader("Case-Access-Token") String accessToken) {
    return cashEconomics.latest(caseId, accessToken);
  }

  @PostMapping("/cases/{caseId}/documents/{documentId}/extractions/{extractionId}/corrections")
  public CorrectionView correctDealTerm(
      @PathVariable String caseId,
      @PathVariable String documentId,
      @PathVariable String extractionId,
      @NotBlank @Size(max = 200) @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestHeader("Case-Access-Token") String accessToken,
      @RequestBody CorrectDealTermRequest request) {
    return dealerOfferVerification.correct(
        caseId, documentId, extractionId, accessToken, idempotencyKey, request);
  }

  @PostMapping("/cases/{caseId}/dealer-offers")
  @ResponseStatus(HttpStatus.CREATED)
  public DealerOfferView verifyDealerOffer(
      @PathVariable String caseId,
      @NotBlank @Size(max = 200) @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestHeader("Case-Access-Token") String accessToken,
      @RequestBody VerifyDealerOfferRequest request) {
    return dealerOfferVerification.verify(caseId, accessToken, idempotencyKey, request);
  }

  @GetMapping("/cases/{caseId}/dealer-offers/latest")
  public DealerOfferView getLatestDealerOffer(
      @PathVariable String caseId,
      @RequestHeader("Case-Access-Token") String accessToken) {
    return dealerOfferVerification.latest(caseId, accessToken);
  }

  @GetMapping("/cases/{caseId}/dealer-offers/{version}")
  public DealerOfferView getDealerOffer(
      @PathVariable String caseId,
      @PathVariable int version,
      @RequestHeader("Case-Access-Token") String accessToken) {
    return dealerOfferVerification.version(caseId, version, accessToken);
  }

  @GetMapping("/cases/{caseId}/documents/{documentId}/extractions/latest")
  public ExtractionView getLatestExtraction(
      @PathVariable String caseId,
      @PathVariable String documentId,
      @RequestHeader("Case-Access-Token") String accessToken) {
    return documentExtractions.latest(caseId, documentId, accessToken);
  }

  @GetMapping("/cases/{caseId}/documents/{documentId}/extractions/{version}")
  public ExtractionView getExtractionVersion(
      @PathVariable String caseId,
      @PathVariable String documentId,
      @PathVariable int version,
      @RequestHeader("Case-Access-Token") String accessToken) {
    return documentExtractions.version(caseId, documentId, version, accessToken);
  }

  @GetMapping("/cases/{caseId}/documents/{documentId}/extractions")
  public java.util.List<ExtractionSummary> getExtractionHistory(
      @PathVariable String caseId,
      @PathVariable String documentId,
      @RequestParam(defaultValue = "10") int limit,
      @RequestHeader("Case-Access-Token") String accessToken) {
    return documentExtractions.history(caseId, documentId, limit, accessToken);
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

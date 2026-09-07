package com.dealanalyzer.buyingcase.persistence;

import com.dealanalyzer.buyingcase.EphemeralBuyingCaseService;
import com.dealanalyzer.buyingcase.IdempotencyConflictException;
import com.dealanalyzer.buyingcase.ResourceNotFoundException;
import com.dealanalyzer.finance.FinanceLoan;
import com.dealanalyzer.finance.FinancingComparison;
import com.dealanalyzer.verification.persistence.DealerOfferPersistence;
import com.dealanalyzer.vehicle.VehicleEvidenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OutsideFinancingService {
  private final EphemeralBuyingCaseService access;
  private final AnalysisCaseRepository cases;
  private final DealerOfferPersistence dealers;
  private final FinancingService financing;
  private final VehicleEvidenceService vehicles;
  private final OutsideFinanceOfferRepository offers;
  private final FinancingComparisonRepository results;
  private final ObjectMapper json;
  public OutsideFinancingService(EphemeralBuyingCaseService access, AnalysisCaseRepository cases,
      DealerOfferPersistence dealers, FinancingService financing, VehicleEvidenceService vehicles,
      OutsideFinanceOfferRepository offers, FinancingComparisonRepository results, ObjectMapper json) {
    this.access = access; this.cases = cases; this.dealers = dealers; this.financing = financing;
    this.vehicles = vehicles; this.offers = offers; this.results = results; this.json = json;
  }
  @Transactional
  public View compare(String caseId, String token, String key, Request request) {
    var buyingCase = cases.findLockedById(caseId).orElseThrow(() -> new ResourceNotFoundException("Buying Case not found"));
    access.reopen(caseId, token);
    if (request == null || !request.confirmed() || request.loan() == null)
      throw invalid("Explicit Buyer confirmation of outside financing inputs is required");
    String fingerprint = dealers.fingerprint(json.valueToTree(request));
    var prior = offers.findByCaseIdAndIdempotencyKey(caseId, key);
    if (prior.isPresent()) {
      if (!prior.get().requestFingerprint.equals(fingerprint)) throw new IdempotencyConflictException();
      return view(prior.get());
    }
    if (!"FINANCE".equals(buyingCase.purchaseMethod)) throw invalid("A FINANCE Buying Case is required");
    if (!("READY_TO_ANALYZE".equals(buyingCase.status)
        || List.of("COMPLETE", "PARTIAL").contains(buyingCase.status) && !buyingCase.coreReviewBlocked))
      throw invalid("A current verified Dealer Offer is required; resolve pending evidence first");
    var dealer = dealers.latestOffer(caseId).orElseThrow(() -> invalid("A verified Dealer Offer is required"));
    var finance = financing.snapshot(caseId).orElseThrow(() -> invalid("Confirm dealer financing first"));
    if (!finance.id().equals(request.dealerFinanceOfferId()) || !finance.dealerOfferId().equals(dealer.id()))
      throw invalid("Comparison must reference current dealer financing and Dealer Offer");
    var vehicle = vehicles.get(caseId, token);
    if (!vehicle.reliableForAnalysis() || dealer.createdAt().isBefore(vehicle.vehicle().confirmedAt()))
      throw invalid("Reverify the Dealer Offer against current vehicle evidence first");
    int version = offers.findTopByCaseIdOrderByVersionDesc(caseId).map(value -> value.version + 1).orElse(1);
    var offer = new OutsideFinanceOfferEntity();
    offer.id = UUID.randomUUID().toString(); offer.caseId = caseId; offer.version = version;
    offer.dealerFinanceOfferId = finance.id(); offer.idempotencyKey = key; offer.requestFingerprint = fingerprint;
    offer.payloadJson = write(request.loan()); offer.createdAt = Instant.now();
    offers.saveAndFlush(offer);
    var result = new FinancingComparisonEntity();
    result.id = UUID.randomUUID().toString(); result.caseId = caseId; result.version = version;
    result.outsideFinanceOfferId = offer.id; result.policyVersion = FinancingComparison.VERSION; result.createdAt = offer.createdAt;
    var view = new View(offer.id, caseId, version, "BUYER_SELECTED_OUTSIDE_FINANCING", "BUYER_CONFIRMED",
        finance, request.loan(), result.id, new FinancingComparison().compare(finance.inputs(), request.loan()), offer.createdAt);
    result.payloadJson = write(view); results.saveAndFlush(result);
    return view;
  }
  @Transactional(readOnly = true)
  public View latest(String caseId, String token) {
    access.reopen(caseId, token);
    return view(offers.findTopByCaseIdOrderByVersionDesc(caseId)
        .orElseThrow(() -> new ResourceNotFoundException("Outside Finance Offer not found")));
  }
  @Transactional(readOnly = true)
  public View version(String caseId, String token, int version) {
    access.reopen(caseId, token);
    return view(offers.findByCaseIdAndVersion(caseId, version)
        .orElseThrow(() -> new ResourceNotFoundException("Outside Finance Offer not found")));
  }
  private View view(OutsideFinanceOfferEntity offer) {
    try { return json.readValue(results.findByCaseIdAndVersion(offer.caseId, offer.version).orElseThrow().payloadJson, View.class); }
    catch (java.io.IOException failure) { throw new IllegalStateException("Unable to read comparison snapshot", failure); }
  }
  private String write(Object value) {
    try { return json.writeValueAsString(value); }
    catch (java.io.IOException failure) { throw new IllegalStateException("Unable to write comparison snapshot", failure); }
  }
  private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message); }
  public record Request(String dealerFinanceOfferId, boolean confirmed, FinanceLoan loan) {}
  public record View(String id, String caseId, int version, String source, String confirmationStatus,
      FinancingService.View dealerFinancing, FinanceLoan inputs, String resultId,
      FinancingComparison.Result result, Instant createdAt) {}
}

package com.dealanalyzer.buyingcase.persistence;

import com.dealanalyzer.buyingcase.EphemeralBuyingCaseService;
import com.dealanalyzer.buyingcase.IdempotencyConflictException;
import com.dealanalyzer.buyingcase.ResourceNotFoundException;
import com.dealanalyzer.finance.FinanceLoan;
import com.dealanalyzer.finance.FinancingCalculator;
import com.dealanalyzer.loan.ChosenLoanOffers;
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
  private final ChosenLoanOffers chosenLoans;
  public OutsideFinancingService(EphemeralBuyingCaseService access, AnalysisCaseRepository cases,
      DealerOfferPersistence dealers, FinancingService financing, VehicleEvidenceService vehicles,
      OutsideFinanceOfferRepository offers, FinancingComparisonRepository results, ObjectMapper json,
      ChosenLoanOffers chosenLoans) {
    this.access = access; this.cases = cases; this.dealers = dealers; this.financing = financing;
    this.vehicles = vehicles; this.offers = offers; this.results = results; this.json = json;
    this.chosenLoans = chosenLoans;
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
    // A chosen pre-approval supplies its own amount, APR, term and fees (LOAN-03); the Buyer confirms the purchase assumptions.
    FinanceLoan loan = request.loan();
    PreApprovalReference preApproval = null;
    if (request.preApprovalId() != null) {
      var chosen = chosenLoans.forCase(caseId, Instant.now()).filter(offer -> offer.offerId().equals(request.preApprovalId()))
          .orElseThrow(() -> invalid("Use this pre-approval as your own financing first; an expired pre-approval cannot be compared"));
      loan = new FinanceLoan(loan.currency(), loan.purchasePriceMinor(), loan.outTheDoorTotalMinor(), loan.netAmountDueMinor(),
          chosen.amountMinor(), chosen.aprPercent(), chosen.termMonths(), "STANDARD_FIXED_RATE", FinancingCalculator.TIMING,
          loan.financedFeesMinor(), chosen.upfrontFeesMinor(), loan.cashPaidAtClosingMinor(), loan.deferredChargesMinor(),
          chosen.lenderPaymentMinor(), loan.financedAddOns());
      preApproval = new PreApprovalReference(chosen.offerId(), chosen.lender(), chosen.source());
    }
    var vehicle = vehicles.get(caseId, token);
    if (!vehicle.reliableForAnalysis() || dealer.createdAt().isBefore(vehicle.vehicle().confirmedAt()))
      throw invalid("Reverify the Dealer Offer against current vehicle evidence first");
    int version = offers.findTopByCaseIdOrderByVersionDesc(caseId).map(value -> value.version + 1).orElse(1);
    var offer = new OutsideFinanceOfferEntity();
    offer.id = UUID.randomUUID().toString(); offer.caseId = caseId; offer.version = version;
    offer.dealerFinanceOfferId = finance.id(); offer.idempotencyKey = key; offer.requestFingerprint = fingerprint;
    offer.payloadJson = write(loan); offer.createdAt = Instant.now();
    offers.saveAndFlush(offer);
    var result = new FinancingComparisonEntity();
    result.id = UUID.randomUUID().toString(); result.caseId = caseId; result.version = version;
    result.outsideFinanceOfferId = offer.id; result.policyVersion = FinancingComparison.VERSION; result.createdAt = offer.createdAt;
    var view = new View(offer.id, caseId, version, preApproval == null ? "BUYER_SELECTED_OUTSIDE_FINANCING" : "BUYER_CHOSEN_PRE_APPROVAL",
        "BUYER_CONFIRMED", finance, loan, result.id, new FinancingComparison().compare(finance.inputs(), loan), offer.createdAt,
        preApproval);
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
  /** {@code preApprovalId}, when given, is the Buyer's chosen pre-approval, whose terms replace the loan's (LOAN-03). */
  public record Request(String dealerFinanceOfferId, boolean confirmed, FinanceLoan loan, String preApprovalId) {}
  public record View(String id, String caseId, int version, String source, String confirmationStatus,
      FinancingService.View dealerFinancing, FinanceLoan inputs, String resultId,
      FinancingComparison.Result result, Instant createdAt, PreApprovalReference preApproval) {}
  /** offerSource: PARTNER or BUYER_ENTERED, as in the loan offers. */
  public record PreApprovalReference(String offerId, String lender, String offerSource) {}
}

package com.dealanalyzer.buyingcase.persistence;

import com.dealanalyzer.buyingcase.EphemeralBuyingCaseService;
import com.dealanalyzer.buyingcase.IdempotencyConflictException;
import com.dealanalyzer.buyingcase.ResourceNotFoundException;
import com.dealanalyzer.finance.FinanceLoan;
import com.dealanalyzer.finance.FinancingCalculator;
import com.dealanalyzer.verification.persistence.DealerOfferPersistence;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FinancingService {
  private final EphemeralBuyingCaseService access;
  private final AnalysisCaseRepository cases;
  private final DealerOfferPersistence dealers;
  private final FinanceOfferRepository offers;
  private final FinancingResultRepository results;
  private final ObjectMapper json;
  public FinancingService(EphemeralBuyingCaseService access, AnalysisCaseRepository cases,
      DealerOfferPersistence dealers, FinanceOfferRepository offers,
      FinancingResultRepository results, ObjectMapper json) {
    this.access = access; this.cases = cases; this.dealers = dealers;
    this.offers = offers; this.results = results; this.json = json;
  }

  @Transactional
  public View verify(String caseId, String token, String key, VerifyRequest request) {
    var buyingCase = cases.findLockedById(caseId).orElseThrow(() -> new ResourceNotFoundException("Buying Case not found"));
    access.reopen(caseId, token);
    if (request == null || !request.confirmed() || request.loan() == null)
      throw invalid("Explicit Buyer confirmation of Finance Offer inputs is required");
    String fingerprint = dealers.fingerprint(json.valueToTree(request));
    var prior = offers.findByCaseIdAndIdempotencyKey(caseId, key);
    if (prior.isPresent()) {
      if (!prior.get().requestFingerprint.equals(fingerprint)) throw new IdempotencyConflictException();
      return view(prior.get());
    }
    if (!"FINANCE".equals(buyingCase.purchaseMethod)) throw invalid("A FINANCE Buying Case is required");
    if (!java.util.List.of("READY_TO_ANALYZE", "COMPLETE", "PARTIAL").contains(buyingCase.status))
      throw invalid("A current verified Dealer Offer is required");
    var dealer = dealers.latestOffer(caseId).orElseThrow(() -> invalid("A verified Dealer Offer is required"));
    if (!dealer.id().equals(request.dealerOfferId())) throw invalid("Finance Offer must reference the current Dealer Offer");
    requireConsistentDealerTerms(dealer.terms(), request.loan());
    int version = offers.findTopByCaseIdOrderByVersionDesc(caseId).map(value -> value.version + 1).orElse(1);
    Instant now = Instant.now();
    var offer = new FinanceOfferEntity();
    offer.id = UUID.randomUUID().toString(); offer.caseId = caseId; offer.version = version;
    offer.dealerOfferId = dealer.id(); offer.idempotencyKey = key; offer.requestFingerprint = fingerprint;
    offer.payloadJson = write(request.loan()); offer.createdAt = now;
    offers.saveAndFlush(offer);
    var result = new FinancingResultEntity();
    result.id = UUID.randomUUID().toString(); result.caseId = caseId; result.version = version;
    result.financeOfferId = offer.id; result.policyVersion = FinancingCalculator.VERSION;
    result.payloadJson = write(new FinancingCalculator().calculate(request.loan())); result.createdAt = now;
    results.saveAndFlush(result);
    return view(offer);
  }

  @Transactional(readOnly = true)
  public View latest(String caseId, String token) {
    access.reopen(caseId, token);
    return snapshot(caseId).orElseThrow(() -> new ResourceNotFoundException("Finance Offer not found"));
  }

  @Transactional(readOnly = true)
  public Optional<View> snapshot(String caseId) {
    return offers.findTopByCaseIdOrderByVersionDesc(caseId).map(this::view);
  }

  private void requireConsistentDealerTerms(com.fasterxml.jackson.databind.JsonNode terms, FinanceLoan loan) {
    for (var candidate : terms.path("candidates")) {
      Number provided = switch (candidate.path("field").asText()) {
        case "AMOUNT_FINANCED" -> loan.principalMinor();
        case "APR" -> loan.aprPercent();
        case "TERM_MONTHS" -> loan.termMonths();
        case "MONTHLY_PAYMENT" -> loan.quotedPaymentMinor();
        case "OUT_THE_DOOR_TOTAL" -> loan.outTheDoorTotalMinor();
        default -> null;
      };
      if (provided == null || candidate.path("value").isNull() || candidate.path("value").asText().isBlank()) continue;
      try {
        if (new java.math.BigDecimal(provided.toString()).compareTo(new java.math.BigDecimal(candidate.path("value").asText())) != 0)
          throw invalid("Finance input conflicts with verified " + candidate.path("field").asText() + "; correct and reverify the Dealer Offer first");
      } catch (NumberFormatException invalidValue) {
        throw invalid("Dealer financing value is not numeric; correct and reverify the Dealer Offer first");
      }
    }
  }

  private View view(FinanceOfferEntity offer) {
    var result = results.findByCaseIdAndVersion(offer.caseId, offer.version).orElseThrow();
    try {
      return new View(offer.id, offer.caseId, offer.version, offer.dealerOfferId, "VERIFIED",
          "BUYER_CONFIRMED_INPUT", json.readValue(offer.payloadJson, FinanceLoan.class), result.id,
          json.readValue(result.payloadJson, FinancingCalculator.Result.class), offer.createdAt);
    } catch (java.io.IOException failure) { throw new IllegalStateException("Unable to read financing snapshot", failure); }
  }
  private String write(Object value) {
    try { return json.writeValueAsString(value); }
    catch (java.io.IOException failure) { throw new IllegalStateException("Unable to write financing snapshot", failure); }
  }
  private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message); }
  public record VerifyRequest(String dealerOfferId, boolean confirmed, FinanceLoan loan) {}
  public record View(String id, String caseId, int version, String dealerOfferId, String verificationStatus,
      String source, FinanceLoan inputs, String resultId, FinancingCalculator.Result result, Instant createdAt) {}
}

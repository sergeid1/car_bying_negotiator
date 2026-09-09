package com.dealanalyzer.buyingcase.persistence;

import com.dealanalyzer.buyingcase.EphemeralBuyingCaseService;
import com.dealanalyzer.buyingcase.IdempotencyConflictException;
import com.dealanalyzer.economics.CashDealEconomics.Result;
import com.dealanalyzer.economics.persistence.CashEconomicsPersistence;
import com.dealanalyzer.market.persistence.MarketAssessmentPersistence;
import com.dealanalyzer.plan.PricePlanService;
import com.dealanalyzer.plan.PriceBoundaryPolicy;
import com.dealanalyzer.report.CoreReviewPolicy;
import com.dealanalyzer.vehicle.VehicleEvidenceService;
import com.dealanalyzer.verification.persistence.DealerOfferPersistence;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One short transaction freezes all locally persisted evidence and inserts one report per operation. */
@Service
public class CoreReportService {
  private final NegotiationService negotiation;
  private final EphemeralBuyingCaseService access;
  private final AnalysisCaseRepository cases;
  private final OperationRepository operations;
  private final AnalysisReportRepository reports;
  private final BuyingCaseStore store;
  private final DealerOfferPersistence offers;
  private final CashEconomicsPersistence economics;
  private final MarketAssessmentPersistence market;
  private final PricePlanService plans;
  private final VehicleEvidenceService vehicles;
  private final ObjectMapper json;
  private final FinancingService financing;
  private final com.dealanalyzer.review.DealReviewService reviews;

  public CoreReportService(EphemeralBuyingCaseService access, AnalysisCaseRepository cases,
      OperationRepository operations, AnalysisReportRepository reports, BuyingCaseStore store,
      DealerOfferPersistence offers, CashEconomicsPersistence economics, MarketAssessmentPersistence market,
      PricePlanService plans, VehicleEvidenceService vehicles, ObjectMapper json, FinancingService financing, com.dealanalyzer.review.DealReviewService reviews, NegotiationService negotiation) {
    this.negotiation = negotiation;
    this.reviews = reviews;
    this.access = access; this.cases = cases; this.operations = operations; this.reports = reports;
    this.store = store; this.offers = offers; this.economics = economics; this.market = market;
    this.financing = financing; this.plans = plans; this.vehicles = vehicles; this.json = json;
  }

  @Transactional
  public StartedReport analyze(String caseId, String token, String key) {
    var buyingCase = cases.findLockedById(caseId)
        .orElseThrow(() -> new com.dealanalyzer.buyingcase.ResourceNotFoundException("Buying Case not found"));
    access.reopen(caseId, token);
    var existing = operations.findByIdempotencyKey(key);
    if (existing.isPresent()) {
      if (!existing.get().caseId.equals(caseId)) throw new IdempotencyConflictException();
      return new StartedReport(store.requireOperation(existing.get().id));
    }
    var offer = offers.latestOffer(caseId).orElse(null);
    boolean financeCase = "FINANCE".equals(buyingCase.purchaseMethod);
    var finance = financeCase ? financing.snapshot(caseId).orElse(null) : null;
    boolean dealerCurrent = offer != null && ("READY_TO_ANALYZE".equals(buyingCase.status)
        || List.of("COMPLETE", "PARTIAL").contains(buyingCase.status) && !buyingCase.coreReviewBlocked);
    boolean financeCurrent = dealerCurrent && finance != null && finance.dealerOfferId().equals(offer.id());
    String financeAvailability = !financeCase ? "NOT_APPLICABLE" : finance == null ? "UNAVAILABLE"
        : !financeCurrent ? "STALE_OR_UNVERIFIED" : finance.result().status();
    var cashView = economics.latest(caseId).orElse(null);
    Result cash = cashView == null ? null : json.convertValue(cashView.result(), Result.class);
    var assessment = market.latest(caseId).orElse(null);
    var baseline = plans.latest(caseId, "BASELINE").orElse(null);
    VehicleEvidenceService.VehicleEvidenceView vehicle = null;
    try { vehicle = vehicles.get(caseId, token); }
    catch (com.dealanalyzer.vehicle.InvalidVehicleEvidenceException missing) { /* Explicitly unavailable below. */ }
    boolean current = ("READY_TO_ANALYZE".equals(buyingCase.status)
        || List.of("COMPLETE", "PARTIAL").contains(buyingCase.status) && !buyingCase.coreReviewBlocked)
        && "CASH".equals(buyingCase.purchaseMethod) && offer != null && cash != null && vehicle != null
        && vehicle.reliableForAnalysis() && offer.status().equals("VERIFIED")
        && !offer.createdAt().isBefore(vehicle.vehicle().confirmedAt())
        && cash.dealerOfferId().equals(offer.id());
    dealerCurrent = dealerCurrent && vehicle != null && vehicle.reliableForAnalysis()
        && offer.status().equals("VERIFIED") && !offer.createdAt().isBefore(vehicle.vehicle().confirmedAt());
    financeCurrent = financeCurrent && dealerCurrent;
    if (finance != null && !financeCurrent) financeAvailability = "STALE_OR_UNVERIFIED";
    boolean available = assessment != null && "AVAILABLE".equals(assessment.status())
        && assessment.range() != null && vehicle != null
        && !assessment.createdAt().isBefore(vehicle.vehicle().confirmedAt());
    var plan = baseline == null ? null : json.convertValue(baseline.payload().get("plan"), PriceBoundaryPolicy.Plan.class);
    Result approvedCash = baseline == null ? null
        : json.convertValue(baseline.payload().at("/cashEconomics/result"), Result.class);
    boolean sameVehicle = baseline == null || vehicle != null && vehicle.equals(json.convertValue(
        baseline.payload().get("vehicleEvidence"), VehicleEvidenceService.VehicleEvidenceView.class));
    String status = new CoreReviewPolicy().review(current && sameVehicle, cash,
        offer == null ? null : offer.terms(), baseline == null ? null : baseline.payload().at("/dealerOffer/terms"),
        approvedCash, plan, available);
    String rating = "INSUFFICIENT_DATA";
    if (available && current && assessment.range().currency().equals(cash.outTheDoorTotal().currency())) {
      long selling = PriceBoundaryPolicy.sellingPrice(cash);
      rating = selling < assessment.range().lowMinor() ? "STRONG_DEAL"
          : selling > assessment.range().highMinor() ? "POOR_DEAL" : "FAIR_NEGOTIATE";
    }
    if (financeCase) status = "AVAILABLE".equals(financeAvailability) ? "REVIEW_REQUIRED" : "UNABLE_TO_VERIFY";
    if (financeCase && available && "AVAILABLE".equals(financeAvailability)
        && finance.inputs().currency().equals(assessment.range().currency())) {
      long selling = finance.inputs().purchasePriceMinor();
      rating = selling < assessment.range().lowMinor() ? "STRONG_DEAL"
          : selling > assessment.range().highMinor() ? "POOR_DEAL" : "FAIR_NEGOTIATE";
    }
    Instant now = Instant.now();
    int version = buyingCase.currentReportVersion + 1;
    String operationId = UUID.randomUUID().toString();
    String completion = (financeCase ? "AVAILABLE".equals(financeAvailability) : current) && available ? "COMPLETE" : "PARTIAL";
    ObjectNode evidence = json.createObjectNode();
    evidence.set("financing", json.valueToTree(finance));
    evidence.set("dealerOffer", json.valueToTree(offer));
    evidence.set("cashEconomics", json.valueToTree(cashView));
    evidence.set("marketAssessment", json.valueToTree(assessment));
    evidence.set("approvedBaseline", json.valueToTree(baseline));
    evidence.set("vehicle", json.valueToTree(vehicle));
    evidence.put("caseStatusAtAnalysis", buyingCase.status);
    evidence.put("buyerJurisdiction", buyingCase.buyerJurisdiction);
    ObjectNode analysis = json.createObjectNode();
    if (offer != null && offer.version() > 1) {
      JsonNode review = reviews.append(caseId, offer, baseline, status, now);
      analysis.set("dealReview", review);
      status = review.path("status").asText();
    }
    analysis.put("reviewStatus", status);
    analysis.put("reviewLabel", "MATCHES_APPROVED_BASELINE".equals(status) ? "Matches approved deal"
        : status.equals("UNABLE_TO_VERIFY") ? "Unable to verify" : "Review required");
    analysis.put("dealRating", rating);
    analysis.put("ratingBasis", "Estimated selling-price comparison; OTD market rating is not available.");
    analysis.set("topReasons", json.valueToTree(new CoreReviewPolicy().reasons(status, available,
        cash != null && cash.reconciliation().withinTolerance())));
    if (financeCase) analysis.set("topReasons", json.valueToTree(List.of(
        "Dealer financing: " + financeAvailability + ".",
        "AVAILABLE".equals(financeAvailability) && Boolean.FALSE.equals(finance.result().withinTolerance())
            ? "Quoted payment differs beyond the recorded tolerance; ask the dealer to reconcile."
            : "AVAILABLE".equals(financeAvailability) ? "Financing uses independently calculated payments and Buyer-confirmed inputs."
            : "Resolve the missing, unsupported or stale financing inputs before relying on loan economics.",
        "Financing is not checked against an approved financing baseline; review the full-term cost.")));
    analysis.put("financingAvailability", financeAvailability);
    analysis.set("financingTerms", json.valueToTree(finance == null ? null : finance.inputs()));
    analysis.set("financing", json.valueToTree(finance == null ? null : finance.result()));
    analysis.put("nextAction", status.equals("MATCHES_APPROVED_BASELINE")
        ? "Confirm the dealer is still offering this exact version before proceeding."
        : "Resolve missing evidence, changed terms, or price-limit questions with the dealer before deciding.");
    analysis.put("marketAvailability", available ? "AVAILABLE" : "UNAVAILABLE");
    analysis.put("cashAvailability", cash == null ? "UNAVAILABLE" : current ? "AVAILABLE" : "STALE_OR_UNVERIFIED");
    List<MaterialAmount> amounts = new ArrayList<>();
    if (finance != null) {
      var inputs = finance.inputs();
      String inputPrecision = financeCurrent && "USD".equals(inputs.currency()) ? "EXACT" : "UNAVAILABLE";
      addFinanceAmount(amounts, "Purchase price", inputs.purchasePriceMinor(), inputPrecision, "/inputs/purchasePriceMinor");
      addFinanceAmount(amounts, "Out-the-door total", inputs.outTheDoorTotalMinor(), inputPrecision, "/inputs/outTheDoorTotalMinor");
      addFinanceAmount(amounts, "Net amount due", inputs.netAmountDueMinor(), inputPrecision, "/inputs/netAmountDueMinor");
      addFinanceAmount(amounts, "Amount financed", inputs.principalMinor(), inputPrecision, "/inputs/principalMinor");
      addFinanceAmount(amounts, "Financed lender fees", inputs.financedFeesMinor(), inputPrecision, "/inputs/financedFeesMinor");
      addFinanceAmount(amounts, "Upfront lender fees (included in cash paid)", inputs.upfrontFeesMinor(), inputPrecision, "/inputs/upfrontFeesMinor");
      addFinanceAmount(amounts, "Cash paid at or before closing", inputs.cashPaidAtClosingMinor(), inputPrecision, "/inputs/cashPaidAtClosingMinor");
      addFinanceAmount(amounts, "Known deferred charges", inputs.deferredChargesMinor(), inputPrecision, "/inputs/deferredChargesMinor");
      String precision = "AVAILABLE".equals(financeAvailability) ? "ESTIMATED" : "UNAVAILABLE";
      var result = finance.result();
      addFinanceAmount(amounts, "Calculated monthly payment", result.paymentMinor(), precision, "/result/paymentMinor");
      addFinanceAmount(amounts, "Final payment", result.finalPaymentMinor(), precision, "/result/finalPaymentMinor");
      addFinanceAmount(amounts, "Total scheduled payments", result.totalScheduledPaymentsMinor(), precision, "/result/totalScheduledPaymentsMinor");
      addFinanceAmount(amounts, "Total interest", result.totalInterestMinor(), precision, "/result/totalInterestMinor");
      addFinanceAmount(amounts, "Total Transaction Cost", result.totalTransactionCostMinor(), precision, "/result/totalTransactionCostMinor");
    }
    if (cashView != null) collectAmounts(cashView.result(), "/result", "cashEconomics", "ESTIMATED", amounts);
    if (available) {
      amounts.add(new MaterialAmount("Market selling-price low", assessment.range().lowMinor(), assessment.range().currency(),
          "ESTIMATED", "marketAssessment", "/range/lowMinor"));
      amounts.add(new MaterialAmount("Market selling-price high", assessment.range().highMinor(), assessment.range().currency(),
          "ESTIMATED", "marketAssessment", "/range/highMinor"));
    } else amounts.add(new MaterialAmount("Market selling-price range", null, "USD", "UNAVAILABLE", "marketAssessment", ""));
    if (cash == null && finance == null) amounts.add(new MaterialAmount("Out-the-door total", null, "USD", "UNAVAILABLE", "cashEconomics", ""));
    if (baseline != null) collectPlanAmounts(baseline.payload().path("plan"), "/payload/plan", plan.currency(), amounts);
    analysis.set("materialAmounts", json.valueToTree(amounts));
    analysis.set("policyVersions", json.valueToTree(Map.of("review", CoreReviewPolicy.VERSION,
        "revisedReview", com.dealanalyzer.review.RevisedOfferPolicy.VERSION,
        "materiality", "core-materiality.v1: zero term/calculation delta; quoted-total tolerance is recorded in cash economics",
        "rating", "selling-price-rating.v1", "cashEconomics", cash == null ? "UNAVAILABLE" : cash.policyVersion(),
        "market", assessment == null ? "UNAVAILABLE" : assessment.methodVersion(),
        "financing", com.dealanalyzer.finance.FinancingCalculator.VERSION,
        "priceBoundaries", plan == null ? "UNAVAILABLE" : plan.policyVersion())));
    analysis.set("limitations", json.valueToTree(List.of(
        financeCase ? "Financing checks cover Buyer-confirmed inputs and the declared schedule as of " + now + "; approved financing limits and independent purchase-charge reconciliation are not covered."
            : "Checks cover the frozen verified offer, approved limits, identity evidence and recorded cash calculation as of " + now + ".",
        "Jurisdiction coverage: " + (cash == null ? "unavailable" : cash.jurisdiction() + " using " + cash.policyVersion()) + ".",
        "Tax and fee rules are recorded estimates; unresolved classifications prevent a matching verdict.",
        "Market provider: " + (assessment == null ? "unavailable" : assessment.provider().source() + "; " + assessment.limitation()),
        "Financing covers only the recorded standard monthly schedule. Mechanical condition, title, legal compliance and purchase safety are excluded. Savings are not guaranteed.")));
    ObjectNode payload = json.createObjectNode();
    payload.put("id", UUID.randomUUID().toString()); payload.put("caseId", caseId);
    payload.put("schemaVersion", "core-deal-analysis-report.v1"); payload.put("version", version);
    payload.put("generatedAt", now.toString()); payload.put("asOf", now.toString()); payload.put("status", completion);
    payload.set("analysis", analysis); payload.set("evidence", evidence);
    String fingerprint = offers.fingerprint(analysis);
    payload.set("reproduction", json.valueToTree(Map.of("inputFingerprint", offers.fingerprint(evidence),
        "analysisFingerprint", fingerprint, "inputs", "#/evidence", "policyVersions", "#/analysis/policyVersions",
        "regenerationDifferences", List.of("id", "version", "generatedAt", "asOf", "review check time"))));
    operations.saveAndFlush(new OperationEntity(operationId, caseId, key, "COMPLETE", now));
    reports.saveAndFlush(new AnalysisReportEntity(caseId, version, write(payload), fingerprint, now, operationId));
    negotiation.append(caseId, version);
    buyingCase.currentReportVersion = version; buyingCase.status = completion; buyingCase.updatedAt = now;
    buyingCase.coreReviewBlocked = financeCase ? !dealerCurrent : !current;
    return new StartedReport(store.requireOperation(operationId));
  }

  private void addFinanceAmount(List<MaterialAmount> amounts, String label, Long value, String precision, String pointer) {
    amounts.add(new MaterialAmount(label, value, "USD", value == null ? "UNAVAILABLE" : precision, "financing", pointer));
  }

  private void collectAmounts(JsonNode node, String path, String source, String precision, List<MaterialAmount> amounts) {
    if (node.isObject() && node.has("label") && node.path("amount").has("minorUnits")) {
      var amount = node.get("amount");
      amounts.add(new MaterialAmount(node.get("label").asText(), amount.get("minorUnits").asLong(),
          amount.get("currency").asText(), "EXACT", source, path + "/amount"));
      return;
    }
    if (node.isObject() && node.has("minorUnits") && node.has("currency")) {
      amounts.add(new MaterialAmount(path, node.get("minorUnits").asLong(), node.get("currency").asText(),
          path.contains("lineItems") || path.contains("quotedTotal") ? "EXACT" : precision, source, path));
    } else if (node.isObject()) node.fields().forEachRemaining(field -> collectAmounts(field.getValue(), path + "/" + field.getKey(), source, precision, amounts));
    else if (node.isArray()) for (int index = 0; index < node.size(); index++) collectAmounts(node.get(index), path + "/" + index, source, precision, amounts);
  }

  private void collectPlanAmounts(JsonNode node, String path, String currency, List<MaterialAmount> amounts) {
    node.fields().forEachRemaining(field -> {
      if (field.getValue().isObject()) collectPlanAmounts(field.getValue(), path + "/" + field.getKey(), currency, amounts);
      else if (field.getValue().isNumber() && field.getKey().endsWith("Minor"))
        amounts.add(new MaterialAmount(path + "/" + field.getKey(), field.getValue().asLong(), currency,
            field.getKey().startsWith("buyerMax") ? "EXACT" : "ESTIMATED", "approvedBaseline", path + "/" + field.getKey()));
    });
  }

  private String write(Object value) {
    try { return json.writeValueAsString(value); }
    catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
  }
  public record StartedReport(BuyingCaseStore.OperationView operation) {}
  public record MaterialAmount(String label, Long minorUnits, String currency, String precision, String source, String pointer) {}
}

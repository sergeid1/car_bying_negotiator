package com.dealanalyzer.desk;

import com.dealanalyzer.buyingcase.EphemeralBuyingCaseService;
import com.dealanalyzer.extraction.persistence.DocumentExtractionPersistence;
import com.dealanalyzer.extraction.persistence.DocumentExtractionPersistence.ExtractionView;
import com.dealanalyzer.plan.PricePlanService;
import com.dealanalyzer.review.RevisedOfferPolicy;
import com.dealanalyzer.trade.desk.TradeDeskCheck;
import com.dealanalyzer.verification.persistence.DealerOfferPersistence;
import com.dealanalyzer.verification.persistence.DealerOfferPersistence.DealerOfferView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Compares the paperwork presented at the desk with the Agreed Price's Approved Baseline. It reuses
 * revised-offer review: the saved Deal Review when the Buyer has verified the paperwork and run the
 * analysis, otherwise {@link RevisedOfferPolicy} over the verified offer or, before verification, over
 * the terms as read, each of which is reported as unverified.
 */
@Service
public class DeskCheckService {
  public static final String VERSION = "desk-check.v1";
  private static final List<String> FINANCING = List.of("AMOUNT_FINANCED", "APR", "TERM_MONTHS",
      "MONTHLY_PAYMENT", "FINANCE_CHARGE", "TOTAL_OF_PAYMENTS", "CASH_DOWN");
  private static final List<String> PRICE = List.of("SELLING_PRICE", "DISCOUNT_TOTAL", "REBATE_TOTAL");
  private static final List<String> TRADE = List.of("TRADE_ALLOWANCE", "TRADE_PAYOFF");
  private static final List<String> TOTALS = List.of("OUT_THE_DOOR_TOTAL", "SUBTOTAL", "TAX_TOTAL",
      "DEALER_CHARGES_TOTAL", "GOVERNMENT_CHARGES_TOTAL", "ADD_ON_TOTAL");
  /** Lead order: a different car first, then money added to the deal, then changed terms. */
  private static final List<String> ORDER = List.of("VEHICLE", "ADDED", "REMOVED", "CHANGED_CHARGE",
      "PRICE", "FINANCING", "TRADE", "LIMIT", "TOTAL");

  private final EphemeralBuyingCaseService cases;
  private final PricePlanService plans;
  private final DealerOfferPersistence offers;
  private final DocumentExtractionPersistence extractions;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final com.dealanalyzer.purchase.PurchaseService purchases;
  private final TradeDeskCheck trade;
  private final com.dealanalyzer.desk.tactic.DeskTacticService tactics;
  private final com.dealanalyzer.listing.capture.ListingPriceCheck listingPrice;
  private final com.dealanalyzer.economics.persistence.CashEconomicsPersistence policies;
  private final com.dealanalyzer.economics.fees.GovernmentFeeCheck governmentFees;

  public DeskCheckService(EphemeralBuyingCaseService cases, PricePlanService plans, DealerOfferPersistence offers,
      DocumentExtractionPersistence extractions, JdbcTemplate jdbc, ObjectMapper json,
      com.dealanalyzer.purchase.PurchaseService purchases, TradeDeskCheck trade,
      com.dealanalyzer.desk.tactic.DeskTacticService tactics,
      com.dealanalyzer.listing.capture.ListingPriceCheck listingPrice,
      com.dealanalyzer.economics.persistence.CashEconomicsPersistence policies,
      com.dealanalyzer.economics.fees.GovernmentFeeCheck governmentFees) {
    this.governmentFees = governmentFees;
    this.listingPrice = listingPrice;
    this.policies = policies;
    this.cases = cases; this.plans = plans; this.offers = offers; this.extractions = extractions;
    this.jdbc = jdbc; this.json = json; this.purchases = purchases; this.trade = trade;
    this.tactics = tactics;
  }

  /** The case's pack rules when its tax is paid at titling (ticket 137); null otherwise or without a pack. */
  private com.dealanalyzer.economics.CashDealEconomics.TaxRules titlingTax(String caseId) {
    String jurisdiction = jdbc.queryForList("select buyer_jurisdiction from analysis_cases where id = ?", String.class, caseId)
        .stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
    if (jurisdiction == null || !policies.hasPolicy(jurisdiction)) return null;
    var rules = policies.requirePolicy(jurisdiction).taxRules();
    return rules.paidAtTitling() ? rules : null;
  }

  public record View(String policyVersion, String status, String source, Agreed agreedPrice, Paperwork paperwork,
      List<Finding> findings, List<Finding> otherDifferences, int unchangedTerms, TotalEffect totalEffect,
      FreshCase freshCase, Integer dealReviewVersion, TradeDeskCheck.Result trade,
      List<com.dealanalyzer.desk.tactic.DeskTacticPolicy.Finding> tacticFindings,
      com.dealanalyzer.listing.capture.ListingPriceCheck.Result listingPrice,
      com.dealanalyzer.economics.fees.GovernmentFeeCheck.Result governmentFees) {}
  public record Agreed(int offerVersion, int baselineVersion, String vin, Long sellingPriceMinor,
      Long outTheDoorMinor, Instant acceptedAt) {}
  public record Paperwork(String documentId, Instant uploadedAt, String extractionId, Integer offerVersion, String vin) {}
  /** One difference from the Agreed Price; {@code verified} only when it rests on Buyer-verified evidence. */
  public record Finding(String group, String term, String label, String kind, JsonNode agreed, JsonNode desk,
      BigDecimal delta, String unit, String severity, boolean verified, JsonNode evidence, String buyerAction) {}
  /** basis: CALCULATED (saved review), QUOTED (totals on the paperwork) or UNAVAILABLE. Missing is never zero. */
  public record TotalEffect(String basis, String currency, Long outTheDoorDeltaMinor, Long preTaxDeltaMinor,
      Long taxEffectMinor, Long addedChargesMinor) {}
  /** The case-start details for the car on the paperwork, kept apart from the agreed car. */
  public record FreshCase(String vin, String dealerLocation, String purchaseMethod) {
    @com.fasterxml.jackson.annotation.JsonProperty("dealerPostalCode")
    public String dealerPostalCode() {
      return dealerLocation != null && dealerLocation.matches("[0-9]{5}") ? dealerLocation : null;
    }
  }
  private record AgreedRow(int offerVersion, int baselineVersion, String baselineId, Instant acceptedAt) {}
  private record CaseRow(String dealerLocation, String purchaseMethod) {}
  private record Document(String id, Instant createdAt) {}

  /** The Agreed Price's Approved Baseline terms and the agreed car's VIN, read under the report purchase gate. */
  record AgreedBaseline(Agreed agreed, String baselineId, JsonNode terms) {}

  /** Empty when the case has no Agreed Price; otherwise its baseline, once the purchase gate allows it. */
  @Transactional(readOnly = true)
  Optional<AgreedBaseline> agreedBaseline(String caseId, String token) {
    var agreedRow = jdbc.query("select offer_version, baseline_version, baseline_id, accepted_at from agreed_prices where case_id = ?",
        (r, n) -> new AgreedRow(r.getInt(1), r.getInt(2), r.getString(3), r.getTimestamp(4).toInstant()), caseId)
        .stream().findFirst().orElse(null);
    if (agreedRow == null) return Optional.empty();
    // The check reads the Approved Baseline and saved Deal Reviews, so it shares their purchase gate.
    purchases.requireReport(caseId, token);
    var baseline = plans.get(caseId, token, "BASELINE", agreedRow.baselineVersion());
    JsonNode agreedTerms = baseline.payload().at("/dealerOffer/terms");
    String agreedVin = text(baseline.payload().at("/vehicleEvidence/vehicle/vin"));
    if (agreedVin == null) agreedVin = candidate(agreedTerms, "VEHICLE_VIN");
    return Optional.of(new AgreedBaseline(new Agreed(agreedRow.offerVersion(), agreedRow.baselineVersion(), agreedVin,
        amount(agreedTerms, "SELLING_PRICE"), amount(agreedTerms, "OUT_THE_DOOR_TOTAL"), agreedRow.acceptedAt()),
        agreedRow.baselineId(), agreedTerms));
  }

  @Transactional(readOnly = true)
  public View view(String caseId, String token) {
    cases.reopen(caseId, token);
    var baseline = agreedBaseline(caseId, token).orElse(null);
    if (baseline == null) return empty("NO_AGREED_PRICE", null);
    var agreed = baseline.agreed();
    var agreedRow = new AgreedRow(agreed.offerVersion(), agreed.baselineVersion(), baseline.baselineId(), agreed.acceptedAt());
    JsonNode agreedTerms = baseline.terms();
    String agreedVin = agreed.vin();
    // Desk paperwork is whatever the Buyer adds after accepting; the accepted quote itself never counts.
    var document = jdbc.query("""
        select id, created_at from source_documents where case_id = ? and purpose = 'DEALER_PAPERWORK' and created_at > ?
        order by created_at desc, id desc fetch first 1 rows only
        """, (r, n) -> new Document(r.getString(1), r.getTimestamp(2).toInstant()), caseId,
        java.sql.Timestamp.from(agreedRow.acceptedAt())).stream().findFirst().orElse(null);
    if (document == null) return empty("AWAITING_PAPERWORK", agreed);
    ExtractionView extraction;
    try { extraction = extractions.requireLatest(caseId, document.id()); }
    catch (com.dealanalyzer.buyingcase.ResourceNotFoundException reading) {
      return new View(VERSION, "READING", null, agreed, new Paperwork(document.id(), document.createdAt(), null, null, null),
          List.of(), List.of(), 0, unavailable(), null, null, null, List.of(), null, null);
    }
    String deskVin = extraction.candidates().stream().filter(c -> c.field().equals("VEHICLE_VIN")).findFirst()
        .map(c -> c.currentCorrection() == null ? c.normalizedValue() : c.currentCorrection().normalizedValue()).orElse(null);
    DealerOfferView offer = offers.latestOffer(caseId)
        .filter(value -> value.version() > agreedRow.offerVersion() && "VERIFIED".equals(value.status())
            && value.extractionId().equals(extraction.id())).orElse(null);
    var paperwork = new Paperwork(document.id(), document.createdAt(), extraction.id(),
        offer == null ? null : offer.version(), deskVin);
    var policy = new RevisedOfferPolicy();
    if (agreedVin != null && deskVin != null && !agreedVin.equals(deskVin)) {
      var place = jdbc.queryForObject("select dealer_location, purchase_method from analysis_cases where id = ?",
          (r, n) -> new CaseRow(r.getString(1), r.getString(2)), caseId);
      ObjectNode agreedValue = json.createObjectNode().put("value", agreedVin).put("unit", "VIN");
      ObjectNode deskValue = json.createObjectNode().put("value", deskVin).put("unit", "VIN");
      var vin = new Finding("VEHICLE", "candidate:VEHICLE_VIN#1", "VEHICLE_VIN", "CHANGED", agreedValue, deskValue,
          null, "VIN", "BLOCKING", false, evidence(extraction, "VEHICLE_VIN"),
          "Start a separate case for this car. Nothing on this paperwork is compared with or added to the agreed car.");
      return new View(VERSION, "DIFFERENT_VIN", "READ_FROM_PAPERWORK", agreed, paperwork, List.of(vin), List.of(), 0,
          unavailable(), new FreshCase(deskVin, place.dealerLocation(), place.purchaseMethod()), null, null, List.of(), null, null);
    }
    String source;
    List<JsonNode> discrepancies = new ArrayList<>();
    JsonNode cash = null;
    Integer reviewVersion = null;
    JsonNode deskTerms;
    var review = offer == null ? null : jdbc.query("""
        select version, payload_json from deal_reviews where case_id = ? and current_offer_id = ? and baseline_id = ?
        order by version desc fetch first 1 rows only
        """, (r, n) -> Map.entry(r.getInt(1), read(r.getString(2))), caseId, offer.id(), agreedRow.baselineId())
        .stream().findFirst().orElse(null);
    if (review != null) {
      source = "DEAL_REVIEW";
      reviewVersion = review.getKey();
      for (JsonNode row : review.getValue().path("discrepancies"))
        if ("BASELINE".equals(row.path("comparison").asText())) discrepancies.add(row);
      cash = review.getValue().at("/cashDeltas/baseline");
      deskTerms = offer.terms();
    } else {
      source = offer == null ? "READ_FROM_PAPERWORK" : "VERIFIED_OFFER";
      deskTerms = offer == null ? asRead(extraction) : offer.terms();
      for (var row : policy.compare("BASELINE", agreedTerms, deskTerms)) discrepancies.add(json.valueToTree(row));
    }
    List<Finding> lead = new ArrayList<>(), other = new ArrayList<>();
    var titlingTax = titlingTax(caseId);
    int differing = 0;
    long added = 0;
    boolean addedKnown = true;
    for (JsonNode row : discrepancies) {
      String term = row.path("term").asText(), kind = row.path("kind").asText();
      boolean line = term.startsWith("line:");
      String field = line ? null : term.replaceFirst("^candidate:", "").replaceFirst("#\\d+$", "");
      JsonNode expected = row.path("expected"), observed = row.path("observed");
      boolean equal = !expected.isMissingNode() && expected.equals(observed);
      String label = line ? (observed.isObject() ? row.at("/observedEvidence/originalLabel") : row.at("/expectedEvidence/originalLabel")).asText(term)
          : field;
      boolean verified = !"READ_FROM_PAPERWORK".equals(source)
          && (observed.isNull() || observed.isMissingNode() || row.at("/observedEvidence/buyerVerified").asBoolean(true));
      String group;
      if (kind.equals("LIMIT_VIOLATION")) group = "LIMIT";
      else if (equal) continue; // Same as agreed; only unverified, which the source already says.
      else if (line && kind.equals("ADDED")) group = "ADDED";
      else if (line && kind.equals("MISSING")) group = "REMOVED";
      // Same amount under a different classification is shown, but does not lead the check.
      else if (line) group = decimal(row.path("delta")) != null && decimal(row.path("delta")).signum() == 0 ? "OTHER" : "CHANGED_CHARGE";
      else if (field.equals("VEHICLE_VIN")) group = "VEHICLE";
      else if (PRICE.contains(field)) group = "PRICE";
      else if (FINANCING.contains(field)) group = "FINANCING";
      else if (TRADE.contains(field)) group = "TRADE";
      else if (TOTALS.contains(field)) group = "TOTAL";
      else group = "OTHER";
      if (!group.equals("ADDED") && !group.equals("LIMIT")) differing++;
      if (group.equals("ADDED")) {
        if (observed.path("amountMinor").isIntegralNumber() && "USD".equals(observed.path("currency").asText()))
          added = Math.addExact(added, observed.path("amountMinor").longValue());
        else addedKnown = false;
      }
      String severity = row.path("severity").asText(), action = row.path("buyerAction").asText(null);
      // A tax paid when the car is titled may be missing from desk paperwork without anything being wrong.
      if (titlingTax != null && kind.equals("MISSING") && (line ? "TAX".equals(expected.path("category").asText())
          : "TAX_TOTAL".equals(field))) {
        group = "OTHER"; severity = "INFORMATIONAL";
        action = titlingTax.buyerNote() + " Ask whether the dealer is collecting the " + titlingTax.taxName()
            + " or you will pay it when you title the car.";
      }
      var finding = new Finding(group, term, label, kind, nullable(expected), nullable(observed), decimal(row.path("delta")),
          row.path("unit").asText(null), severity, verified,
          nullable(row.at("/observedEvidence/evidence")), action);
      (group.equals("OTHER") ? other : lead).add(finding);
    }
    // The trade check (ticket 103) leads the TRADE group, ahead of totals; its terms rest on the same source.
    var tradeCheck = trade.check(caseId, token, deskTerms, agreedTerms);
    boolean tradeVerified = !"READ_FROM_PAPERWORK".equals(source);
    JsonNode allowanceEvidence = evidence(extraction, "TRADE_ALLOWANCE");
    for (var item : tradeCheck.leading())
      lead.add(new Finding("TRADE", "trade:" + item.code(), item.code(), "TRADE_CHECK", null, null,
          item.amountMinor() == null ? null : BigDecimal.valueOf(item.amountMinor()), "USD_MINOR", item.severity(),
          tradeVerified, allowanceEvidence, item.deskQuestion()));
    lead.sort(Comparator.comparingInt((Finding finding) -> ORDER.indexOf(finding.group()))
        .thenComparing(finding -> finding.term().startsWith("trade:") ? 0 : 1));
    Long addedCharges = addedKnown ? added : null;
    TotalEffect effect;
    if (cash != null && "ESTIMATED".equals(cash.path("availability").asText()))
      effect = new TotalEffect("CALCULATED", cash.path("currency").asText(), cash.path("outTheDoorDeltaMinor").longValue(),
          cash.path("preTaxDeltaMinor").longValue(), cash.path("taxEffectMinor").longValue(), addedCharges);
    else {
      Long desk = amount(deskTerms, "OUT_THE_DOOR_TOTAL");
      effect = desk == null || agreed.outTheDoorMinor() == null
          ? new TotalEffect("UNAVAILABLE", "USD", null, null, null, addedCharges)
          : new TotalEffect("QUOTED", "USD", Math.subtractExact(desk, agreed.outTheDoorMinor()), null, null, addedCharges);
    }
    return new View(VERSION, lead.isEmpty() ? "NO_DIFFERENCES" : "DIFFERENCES", source, agreed, paperwork,
        List.copyOf(lead), List.copyOf(other), Math.max(0, count(agreedTerms) - differing), effect, null, reviewVersion,
        "NO_ALLOWANCE".equals(tradeCheck.status()) ? null : tradeCheck,
        tactics.findings(caseId, deskTerms, agreedTerms, null, tradeCheck),
        // The advertised price (ticket 115) is shown beside the check; it is not a difference from the Agreed Price.
        listingPrice.check(caseId, deskTerms, "DESK_PAPERWORK", !"READ_FROM_PAPERWORK".equals(source)),
        // The state fee lines against the state's fee schedule (ticket 139), beside the comparison.
        governmentFees.check(caseId, deskTerms, !"READ_FROM_PAPERWORK".equals(source)));
  }

  /** The extraction in the verified-offer term shape, every value marked as not yet verified by the Buyer. */
  JsonNode asRead(ExtractionView extraction) {
    ObjectNode terms = json.createObjectNode();
    ArrayNode candidates = terms.putArray("candidates");
    for (var candidate : extraction.candidates()) {
      var correction = candidate.currentCorrection();
      ObjectNode term = candidates.addObject();
      term.put("field", candidate.field());
      term.put("value", correction == null ? candidate.normalizedValue() : correction.normalizedValue());
      term.put("unit", correction == null ? candidate.normalizedUnit() : correction.normalizedUnit());
      term.put("originalWording", candidate.originalWording());
      term.put("confidence", candidate.confidence());
      term.set("evidence", json.valueToTree(candidate.evidence()));
      term.put("buyerVerified", false);
    }
    ArrayNode lines = terms.putArray("lineItems");
    for (var line : extraction.lineItems()) {
      var correction = line.currentCorrection();
      ObjectNode term = lines.addObject();
      term.put("originalLabel", line.originalLabel());
      term.put("category", correction == null ? line.category() : correction.category());
      term.put("amountMinor", correction == null ? line.amountMinor() : correction.amountMinor());
      term.put("currency", line.currency());
      term.put("taxClaim", line.taxClaim());
      term.put("optionalityClaim", line.optionalityClaim());
      term.put("confidence", line.confidence());
      term.set("evidence", json.valueToTree(line.evidence()));
      term.put("buyerVerified", false);
    }
    return terms;
  }

  private View empty(String status, Agreed agreed) {
    return new View(VERSION, status, null, agreed, null, List.of(), List.of(), 0, unavailable(), null, null, null, List.of(), null, null);
  }
  private static TotalEffect unavailable() { return new TotalEffect("UNAVAILABLE", "USD", null, null, null, null); }
  private JsonNode evidence(ExtractionView extraction, String field) {
    return extraction.candidates().stream().filter(c -> c.field().equals(field)).findFirst()
        .map(c -> (JsonNode) json.valueToTree(c.evidence())).orElse(null);
  }
  private static int count(JsonNode terms) { return terms.path("candidates").size() + terms.path("lineItems").size(); }
  private static JsonNode nullable(JsonNode value) { return value == null || value.isMissingNode() || value.isNull() ? null : value; }
  private static String text(JsonNode value) { return value.isTextual() && !value.asText().isBlank() ? value.asText() : null; }
  private static BigDecimal decimal(JsonNode value) {
    return value.isNumber() ? value.decimalValue() : null;
  }
  private static String candidate(JsonNode terms, String field) {
    for (JsonNode term : terms.path("candidates"))
      if (term.path("field").asText().equals(field)) return text(term.path("value"));
    return null;
  }
  private static Long amount(JsonNode terms, String field) {
    String value = candidate(terms, field);
    try { return value == null ? null : Long.valueOf(value); }
    catch (NumberFormatException unreadable) { return null; }
  }
  private JsonNode read(String payload) {
    try { return json.readTree(payload); }
    catch (java.io.IOException failure) { throw new IllegalStateException("Unable to read Deal Review", failure); }
  }
}

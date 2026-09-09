package com.dealanalyzer.review;

import com.dealanalyzer.buyingcase.EphemeralBuyingCaseService;
import com.dealanalyzer.buyingcase.ResourceNotFoundException;
import com.dealanalyzer.economics.persistence.CashEconomicsPersistence;
import com.dealanalyzer.plan.PricePlanService.Snapshot;
import com.dealanalyzer.verification.persistence.DealerOfferPersistence;
import com.dealanalyzer.verification.persistence.DealerOfferPersistence.DealerOfferView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DealReviewService {
  private final DealReviewRepository reviews;
  private final DealerOfferPersistence offers;
  private final CashEconomicsPersistence economics;
  private final EphemeralBuyingCaseService access;
  private final ObjectMapper json;

  public DealReviewService(DealReviewRepository reviews, DealerOfferPersistence offers,
      CashEconomicsPersistence economics, EphemeralBuyingCaseService access, ObjectMapper json) {
    this.reviews = reviews; this.offers = offers; this.economics = economics;
    this.access = access; this.json = json;
  }

  /** Called within the report transaction, under the case lock and operation idempotency check. */
  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
  public JsonNode append(String caseId, DealerOfferView current, Snapshot baseline, String status, Instant now) {
    var previous = offers.requireOffer(caseId, current.version() - 1);
    var policy = new RevisedOfferPolicy();
    var discrepancies = new ArrayList<RevisedOfferPolicy.Discrepancy>();
    if (baseline != null)
      discrepancies.addAll(policy.compare("BASELINE", baseline.payload().at("/dealerOffer/terms"), current.terms()));
    discrepancies.addAll(policy.compare("PREVIOUS", previous.terms(), current.terms()));
    var currentCash = economics.forOffer(current.id()).map(v -> v.result()).orElse(null);
    var previousCash = economics.forOffer(previous.id()).map(v -> v.result()).orElse(null);
    if (baseline != null) {
      JsonNode plan = baseline.payload().path("plan");
      Long selling = currentCash == null ? candidateAmount(current.terms(), "SELLING_PRICE")
          : selling(currentCash);
      Long otd = currentCash == null ? candidateAmount(current.terms(), "OUT_THE_DOOR_TOTAL")
          : currentCash.at("/outTheDoorTotal/minorUnits").longValue();
      // A lower recomputation cannot excuse a higher dealer quote (or the reverse).
      selling = maximum(selling, candidateAmount(current.terms(), "SELLING_PRICE"));
      otd = maximum(otd, candidateAmount(current.terms(), "OUT_THE_DOOR_TOTAL"));
      limit(discrepancies, "sellingPrice.walkAwayMinor", plan.at("/sellingPrice/walkAwayMinor"), selling, baseline, current);
      limit(discrepancies, "buyerMaxSellingPriceMinor", plan.path("buyerMaxSellingPriceMinor"), selling, baseline, current);
      limit(discrepancies, "outTheDoor.walkAwayMinor", plan.at("/outTheDoor/walkAwayMinor"), otd, baseline, current);
      limit(discrepancies, "buyerMaxOutTheDoorPriceMinor", plan.path("buyerMaxOutTheDoorPriceMinor"), otd, baseline, current);
    }
    int version = reviews.findTopByCaseIdOrderByVersionDesc(caseId).map(v -> v.version + 1).orElse(1);
    String id = UUID.randomUUID().toString();
    ObjectNode payload = json.createObjectNode();
    payload.put("id", id); payload.put("caseId", caseId); payload.put("version", version);
    payload.put("baselineId", baseline == null ? null : baseline.id());
    if (baseline == null) payload.putNull("baselineVersion"); else payload.put("baselineVersion", baseline.version());
    payload.put("currentOfferId", current.id()); payload.put("currentOfferVersion", current.version());
    payload.put("previousOfferId", previous.id()); payload.put("previousOfferVersion", previous.version());
    payload.put("policyVersion", RevisedOfferPolicy.VERSION); payload.put("tolerancePolicy", RevisedOfferPolicy.TOLERANCE);
    payload.put("checkedAt", now.toString());
    payload.put("scope", "Frozen verified document terms against Approved Baseline and immediately preceding offer; cash deltas use recorded calculations. Financing schedule and purchase safety are excluded.");
    payload.put("status", baseline == null ? "UNABLE_TO_VERIFY" : status.equals("MATCHES_APPROVED_BASELINE")
        && !discrepancies.isEmpty() ? "REVIEW_REQUIRED" : status);
    payload.set("discrepancies", json.valueToTree(discrepancies));
    ObjectNode totals = payload.putObject("cashDeltas");
    totals.set("baseline", policy.compareCash(baseline == null ? null : baseline.payload().at("/cashEconomics/result"), currentCash));
    totals.set("previous", policy.compareCash(previousCash, currentCash));
    ObjectNode evidence = payload.putObject("evidence");
    evidence.set("currentOffer", json.valueToTree(current));
    evidence.set("previousOffer", json.valueToTree(previous));
    evidence.set("approvedBaseline", json.valueToTree(baseline));
    evidence.set("currentCash", currentCash); evidence.set("previousCash", previousCash);
    reviews.saveAndFlush(new DealReviewEntity(id, caseId, version, baseline == null ? null : baseline.id(),
        previous.id(), current.id(), payload.toString(), now));
    return payload;
  }

  private Long maximum(Long first, Long second) {
    return first == null ? second : second == null ? first : Math.max(first, second);
  }

  private Long candidateAmount(JsonNode terms, String field) {
    for (var candidate : terms.path("candidates"))
      if (candidate.path("field").asText().equals(field) && candidate.path("unit").asText().equals("USD_MINOR"))
        try { return Long.valueOf(candidate.path("value").asText()); }
        catch (NumberFormatException missing) { return null; }
    return null;
  }

  private long selling(JsonNode cash) {
    long result = 0;
    for (var line : cash.path("lineItems"))
      if (line.path("type").asText().equals("SELLING_PRICE"))
        result = Math.addExact(result, line.at("/amount/minorUnits").longValue());
    return result;
  }

  private void limit(List<RevisedOfferPolicy.Discrepancy> discrepancies, String name, JsonNode ceiling,
      Long observed, Snapshot baseline, DealerOfferView current) {
    if (!ceiling.isNumber() || observed == null || observed <= ceiling.longValue()) return;
    discrepancies.add(new RevisedOfferPolicy.Discrepancy("BASELINE", name, "LIMIT_VIOLATION", ceiling,
        json.valueToTree(observed), BigDecimal.valueOf(observed).subtract(ceiling.decimalValue()), "USD_MINOR",
        "MATERIAL", "The revised offer exceeds the approved ceiling. Dealer changes cannot raise Buyer limits.",
        json.valueToTree(Map.of("baselineId", baseline.id(), "pointer", "#/evidence/approvedBaseline/payload/plan/" + name.replace('.', '/'))),
        json.valueToTree(Map.of("offerId", current.id(), "pointer", "#/evidence/currentOffer",
            "calculationPointer", "#/evidence/currentCash", "basis", "Higher of quoted and recomputed amount")), null, false,
        "Ask the dealer to reduce the offer within the approved limit."));
  }

  @Transactional(readOnly = true)
  public JsonNode get(String caseId, String token, Integer version) {
    access.reopen(caseId, token);
    var entity = (version == null ? reviews.findTopByCaseIdOrderByVersionDesc(caseId)
        : reviews.findByCaseIdAndVersion(caseId, version))
        .orElseThrow(() -> new ResourceNotFoundException("Deal Review not found"));
    try { return json.readTree(entity.payloadJson); }
    catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
  }
}

package com.dealanalyzer.report;

import com.dealanalyzer.economics.CashDealEconomics.Result;
import com.dealanalyzer.economics.CashDealEconomics.TaxTreatment;
import com.dealanalyzer.plan.PriceBoundaryPolicy;
import com.dealanalyzer.plan.PriceBoundaryPolicy.Plan;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Fail closed: v1 requires identical frozen terms and calculations (zero hidden tolerance). */
public final class CoreReviewPolicy {
  public static final String VERSION = "core-review.v1";
  public String review(boolean currentVerifiedEvidence, Result cash, JsonNode offerTerms,
      JsonNode approvedTerms, Result approvedCash, Plan plan, boolean marketAvailable) {
    if (!currentVerifiedEvidence || cash == null) return "UNABLE_TO_VERIFY";
    if (cash.unknownCharges().minorUnits() != 0
        || cash.lineItems().stream().anyMatch(line -> line.taxTreatment() == TaxTreatment.UNKNOWN))
      return "UNABLE_TO_VERIFY";
    if (approvedTerms == null || plan == null || approvedCash == null) return "REVIEW_REQUIRED";
    if (cash.reconciliation().quotedTotal() == null || !cash.reconciliation().withinTolerance())
      return "REVIEW_REQUIRED";
    long selling = PriceBoundaryPolicy.sellingPrice(cash);
    long otd = cash.outTheDoorTotal().minorUnits();
    if (selling > plan.sellingPrice().walkAwayMinor()
        || plan.buyerMaxSellingPriceMinor() != null && selling > plan.buyerMaxSellingPriceMinor()
        || plan.outTheDoor() != null && otd > plan.outTheDoor().walkAwayMinor()
        || plan.buyerMaxOutTheDoorPriceMinor() != null && otd > plan.buyerMaxOutTheDoorPriceMinor())
      return "REVIEW_REQUIRED";
    // Including evidence identities deliberately sends revised offers to review in the core report.
    if (!offerTerms.equals(approvedTerms) || !cash.equals(approvedCash) || !marketAvailable)
      return "REVIEW_REQUIRED";
    return "MATCHES_APPROVED_BASELINE";
  }

  public List<String> reasons(String status, boolean marketAvailable, boolean reconciled) {
    return List.of(switch (status) {
      case "MATCHES_APPROVED_BASELINE" -> "Verified terms and cash calculation match the approved version within both approved ceilings.";
      case "UNABLE_TO_VERIFY" -> "Missing, stale, conflicting, or unclassified evidence prevents reliable verification.";
      default -> "Approval, term differences, or approved price limits need Buyer review.";
    }, reconciled ? "The quoted OTD reconciles under the recorded jurisdiction policy."
        : "The quoted OTD is missing or differs from the recorded cash calculation.",
        marketAvailable ? "The market rating is a separate estimated selling-price comparison."
            : "Market analysis is unavailable; available cash economics remain visible.");
  }
}

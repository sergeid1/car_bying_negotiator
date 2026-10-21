package com.dealanalyzer.negotiation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Controlled language over finalized findings. Never interpolates document or provider prose. */
public final class GroundedLanguage {
  public static final String VERSION = "grounded-language.v1";
  public static final String SCOPE = "These explanations concern financial consistency only. They do not establish legal compliance, mechanical condition, title, vehicle history or purchase safety. Savings are not guaranteed.";

  public record Item(String sourcePointer, String sourceValue, String explanation, String question) {}
  public record Language(String policyVersion, List<Item> items, String draft, String scope) {
    public Language { items = List.copyOf(items); }
  }

  public Language generate(JsonNode report) {
    if (!com.dealanalyzer.explanation.ExplanationFacts.supports(report))
      throw new IllegalArgumentException("Finalized core report required");
    List<Item> items = new ArrayList<>();
    String review = report.at("/analysis/reviewStatus").asText();
    switch (review) {
      case "UNABLE_TO_VERIFY" -> add(items, report, "/analysis/reviewStatus",
          "This report cannot verify the deal against the approved terms.",
          "Could you provide a current itemized written offer?");
      case "REVIEW_REQUIRED" -> add(items, report, "/analysis/reviewStatus",
          "The financial checks require review before you decide.",
          "Could you walk me through the current written terms and any changes?");
      case "MATCHES_APPROVED_BASELINE" -> add(items, report, "/analysis/reviewStatus",
          "The recorded financial checks match the approved deal within their stated scope.",
          "Could you confirm whether this exact written offer is still available?");
      default -> throw new IllegalArgumentException("Unknown finalized review status");
    }
    String finance = report.at("/analysis/financingAvailability").asText();
    if ("AVAILABLE".equals(finance)) {
      if (report.at("/analysis/financing/withinTolerance").isBoolean()
          && !report.at("/analysis/financing/withinTolerance").booleanValue()) {
        add(items, report, "/analysis/financing/withinTolerance",
            "The quoted payment differs from the calculated payment beyond the recorded tolerance.",
            "Could you reconcile the quoted payment with the recorded loan terms and payment schedule?");
      }
      add(items, report, "/analysis/financingAvailability",
          "The report provides calculated financing costs for the verified inputs and recorded schedule.",
          "Could you confirm the full payment schedule and full-term cost in writing?");
      if (!report.at("/analysis/financingTerms/financedAddOns").isEmpty()
          && report.at("/analysis/financingTerms/financedAddOns").isArray())
        add(items, report, "/analysis/financingTerms/financedAddOns",
            "The verified financing inputs include financed add-ons.",
            "Could you provide an itemized quote without the financed add-ons for comparison?");
    } else if (!"NOT_APPLICABLE".equals(finance)) {
      if (!List.of("UNAVAILABLE", "STALE_OR_UNVERIFIED", "UNSUPPORTED").contains(finance))
        throw new IllegalArgumentException("Unknown financing availability");
      add(items, report, "/analysis/financingAvailability",
          "Financing costs are unavailable for reliance in this report.",
          "Could you provide complete current financing terms and the payment schedule?");
    }
    if ("STALE_OR_UNVERIFIED".equals(report.at("/analysis/cashAvailability").asText()))
      add(items, report, "/analysis/cashAvailability",
          "The saved cash calculation is stale or unverified for this report.",
          "Could you itemize the current purchase price, taxes, fees and total due?");
    if ("UNAVAILABLE".equals(report.at("/analysis/marketAvailability").asText()))
      add(items, report, "/analysis/marketAvailability",
          "A market comparison is unavailable; this report does not establish a market price advantage.",
          "Is there flexibility in the written selling price?");
    if ("AVAILABLE".equals(report.at("/analysis/marketAvailability").asText())
        && "POOR_DEAL".equals(report.at("/analysis/dealRating").asText()))
      add(items, report, "/analysis/dealRating",
          "The verified selling price is above the estimated market selling-price range. This is not an out-the-door comparison.",
          "Would you consider a lower selling price in a revised written offer?");
    String draft = items.stream().map(Item::question).collect(Collectors.joining("\n\n"));
    return new Language(VERSION, items, draft, SCOPE);
  }

  /** Exact template and source matching rejects even nonnumeric unsupported claims. */
  public void validate(JsonNode finalizedReport, Language candidate) {
    if (!generate(finalizedReport).equals(candidate))
      throw new IllegalArgumentException("Generated claims do not match finalized findings");
  }

  private void add(List<Item> items, JsonNode report, String pointer, String explanation, String question) {
    JsonNode source = report.at(pointer);
    if (source.isMissingNode() || source.isNull()) throw new IllegalArgumentException("Missing finding: " + pointer);
    items.add(new Item(pointer, source.toString(), explanation, question));
  }
}

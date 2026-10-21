package com.dealanalyzer.negotiation;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * A supervised counteroffer draft in the outbound gate's closed language. It extends the grounded
 * reply assistance: questions come only from the finalized report's {@link GroundedLanguage}, and the
 * only figure is the Buyer's mandate target, proposed only while it is below the verified selling price
 * and within the report's approved selling-price boundary. Nothing here reads dealer prose or model output.
 */
public final class Counteroffer {
  public static final String VERSION = "supervised-counteroffer.v1";
  public static final String ITEMIZED_OFFER = "Could you provide a current itemized written offer?";
  static final String LOWER_PRICE = "Would you consider a lower selling price in a revised written offer?";

  /** One paragraph of the draft and where it comes from: a report pointer, the mandate target, or a fixed question. */
  public record Paragraph(String text, String source) {}
  public record Draft(String policyVersion, int reportVersion, List<Paragraph> paragraphs, Long proposalMinor, String text) {
    public Draft { paragraphs = List.copyOf(paragraphs); }
  }

  /** The verified quote is at or below the Buyer's target: negotiation stops and no counter is drafted. */
  public static final class TargetReached extends RuntimeException {
    public TargetReached() { super("The verified quote is at or below your target. Negotiation stopped; you can accept the written quote."); }
  }

  private Counteroffer() {}

  public static boolean targetReached(Long verifiedSellingMinor, long targetMinor) {
    return verifiedSellingMinor != null && verifiedSellingMinor <= targetMinor;
  }

  /**
   * @param report the latest finalized Deal Analysis Report for the current verified offer
   * @param currency the mandate currency; only USD reports carry citable boundaries
   * @param targetMinor the active mandate's target
   * @param ceilingMinor the active mandate's walk-away ceiling
   * @param verifiedSellingMinor the Buyer-verified selling price of the current Dealer Offer, if recorded
   */
  public static Draft draft(JsonNode report, String currency, long targetMinor, long ceilingMinor, Long verifiedSellingMinor) {
    return draft(report, currency, targetMinor, ceilingMinor, verifiedSellingMinor, "en");
  }

  /** The proposed selling price in the gate's price template, in the dealer's email language. */
  public static String priceQuestion(String currency, long minor, String language) {
    String amount = currency + " " + BigDecimal.valueOf(minor, 2).toPlainString();
    return "es".equals(language)
        ? "¿Consideraría " + amount + " como precio de venta en una oferta revisada por escrito?"
        : "Would you consider " + amount + " as the selling price in a revised written offer?";
  }

  /** As {@link #draft(JsonNode, String, long, long, Long)}, with every paragraph in the dealer's email language. */
  public static Draft draft(JsonNode report, String currency, long targetMinor, long ceilingMinor, Long verifiedSellingMinor,
      String language) {
    if (targetReached(verifiedSellingMinor, targetMinor)) throw new TargetReached();
    ReplyGrounds grounds = ReplyGrounds.from(report);
    var grounded = new GroundedLanguage().generate(report);
    var boundary = grounds.boundary();
    Long proposal = null;
    if (verifiedSellingMinor != null && targetMinor > 0 && targetMinor <= ceilingMinor && "USD".equals(currency)
        && (boundary == null || targetMinor <= boundary.ceilingMinor())) proposal = targetMinor;
    List<Paragraph> paragraphs = new ArrayList<>();
    var seen = new LinkedHashSet<String>();
    if (proposal != null) {
      String text = priceQuestion(currency, proposal, language);
      paragraphs.add(new Paragraph(text, "mandate:targetMinor"));
      seen.add(text);
      seen.add(GroundedLanguage.question(LOWER_PRICE, language));
    }
    for (var item : grounded.items()) {
      String question = GroundedLanguage.question(item.question(), language);
      if (seen.add(question)) paragraphs.add(new Paragraph(question, "report:" + item.sourcePointer()));
    }
    String itemized = GroundedLanguage.question(ITEMIZED_OFFER, language);
    if (seen.add(itemized)) paragraphs.add(new Paragraph(itemized, "gate:fixed-question"));
    String text = String.join("\n\n", paragraphs.stream().map(Paragraph::text).toList());
    return new Draft(VERSION, grounds.reportVersion(), paragraphs, proposal, text);
  }
}

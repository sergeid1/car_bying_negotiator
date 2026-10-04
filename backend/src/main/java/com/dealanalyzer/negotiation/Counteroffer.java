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
    if (targetReached(verifiedSellingMinor, targetMinor)) throw new TargetReached();
    ReplyGrounds grounds = ReplyGrounds.from(report);
    var language = new GroundedLanguage().generate(report);
    var boundary = grounds.boundary();
    Long proposal = null;
    if (verifiedSellingMinor != null && targetMinor > 0 && targetMinor <= ceilingMinor && "USD".equals(currency)
        && (boundary == null || targetMinor <= boundary.ceilingMinor())) proposal = targetMinor;
    List<Paragraph> paragraphs = new ArrayList<>();
    var seen = new LinkedHashSet<String>();
    if (proposal != null) {
      String text = "Would you consider " + currency + " " + BigDecimal.valueOf(proposal, 2).toPlainString()
          + " as the selling price in a revised written offer?";
      paragraphs.add(new Paragraph(text, "mandate:targetMinor"));
      seen.add(text);
      seen.add(LOWER_PRICE);
    }
    for (var item : language.items())
      if (seen.add(item.question())) paragraphs.add(new Paragraph(item.question(), "report:" + item.sourcePointer()));
    if (seen.add(ITEMIZED_OFFER)) paragraphs.add(new Paragraph(ITEMIZED_OFFER, "gate:fixed-question"));
    String text = String.join("\n\n", paragraphs.stream().map(Paragraph::text).toList());
    return new Draft(VERSION, grounds.reportVersion(), paragraphs, proposal, text);
  }
}

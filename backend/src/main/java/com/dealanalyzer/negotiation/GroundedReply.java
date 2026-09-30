package com.dealanalyzer.negotiation;

import com.dealanalyzer.negotiation.ReplyDraft.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The template reply and the product-owned wording shared by both producers. The template reads
 * only the recorded report through {@link ReplyGrounds}; it never reads or repeats the Dealer's answer.
 */
public final class GroundedReply {
  public static final String VERSION = "grounded-reply.v1";
  public static final String SCOPE = "This reply states only figures recorded in this report and stays within your "
      + "approved limits. You edit and send it yourself; the product does not contact the Dealer. It does not "
      + "establish legal compliance, mechanical condition, title, vehicle history or purchase safety.";

  static final String OPENING = "Thank you for your response.";

  private GroundedReply() {}

  public static String proposalSentence(long sellingPriceMinor) {
    return "I would consider a revised written offer with a selling price of "
        + ReplyGrounds.dollars(sellingPriceMinor) + ".";
  }

  /** The counter the template proposes: the approved target, never above the ceiling or the Dealer's figure. */
  static Long templateCounter(ReplyGrounds grounds) {
    var boundary = grounds.boundary();
    if (boundary == null) return null;
    long counter = Math.min(boundary.targetMinor(), boundary.ceilingMinor());
    Long current = grounds.currentSellingPriceMinor();
    if (counter <= 0 || current != null && counter >= current) return null;
    return counter;
  }

  public static ReplyCheck.Candidate template(ReplyGrounds grounds) {
    List<String> facts = new ArrayList<>();
    for (String id : List.of(ReplyGrounds.SELLING_PRICE, ReplyGrounds.OUT_THE_DOOR, ReplyGrounds.MARKET_RANGE,
        ReplyGrounds.PAYMENT_MISMATCH)) {
      if (grounds.fact(id) != null) facts.add(id);
    }
    Long counter = templateCounter(grounds);
    List<String> questions = new ArrayList<>();
    questions.add(counter != null
        ? "Could you send a revised itemized written offer at that selling price, including the out-the-door total?"
        : "Could you send a current itemized written offer, including the out-the-door total?");
    if (grounds.fact(ReplyGrounds.PAYMENT_MISMATCH) != null)
      questions.add("Could you reconcile the quoted payment with the written loan terms and payment schedule?");
    return new ReplyCheck.Candidate(OPENING, facts, questions, counter, "");
  }

  /** Opening, stated facts with any counter, then each question on its own line, then the closing. */
  static String text(List<Statement> statements) {
    List<String> paragraphs = new ArrayList<>();
    String body = statements.stream()
        .filter(s -> s.kind() == ReplyDraft.Kind.FACT || s.kind() == ReplyDraft.Kind.PROPOSAL)
        .map(Statement::text).collect(Collectors.joining(" "));
    statements.stream().filter(s -> s.kind() == ReplyDraft.Kind.OPENING).forEach(s -> paragraphs.add(s.text()));
    if (!body.isEmpty()) paragraphs.add(body);
    statements.stream().filter(s -> s.kind() == ReplyDraft.Kind.QUESTION).forEach(s -> paragraphs.add(s.text()));
    statements.stream().filter(s -> s.kind() == ReplyDraft.Kind.CLOSING).forEach(s -> paragraphs.add(s.text()));
    return String.join("\n\n", paragraphs);
  }
}

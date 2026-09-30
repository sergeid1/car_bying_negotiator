package com.dealanalyzer.negotiation;

import com.dealanalyzer.negotiation.ReplyDraft.Kind;
import com.dealanalyzer.negotiation.ReplyDraft.Proposal;
import com.dealanalyzer.negotiation.ReplyDraft.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The code-enforced gate every reply passes, whichever producer wrote it. Facts are stated only in
 * the product's own wording for a recorded source; free text may carry no figure and no claim-like
 * language, so a claim that cannot be grounded is dropped rather than asserted. A counter above the
 * approved limit rejects the whole candidate, because a reply must never propose one.
 */
public final class ReplyCheck {
  /** A candidate from either producer: fact identifiers, free text, and an optional counter. */
  public record Candidate(String opening, List<String> factIds, List<String> questions,
      Long proposedSellingPriceMinor, String closing) {
    public Candidate {
      factIds = List.copyOf(factIds);
      questions = List.copyOf(questions);
    }
  }

  public record Checked(List<Statement> statements, int omitted, Proposal proposal) {}

  /** A candidate that cannot be offered at all. The reason is a code, never candidate text. */
  public static final class Rejected extends RuntimeException {
    private final String reason;
    public Rejected(String reason) { super("Reply rejected: " + reason); this.reason = reason; }
    public String reason() { return reason; }
  }

  static final int MAX_COURTESY = 200;
  static final int MAX_QUESTION = 300;

  private static final Pattern FIGURES = Pattern.compile("[0-9%$€£¥]|\\bpercent\\b");

  /** Spelled-out amounts would bypass the figure check. */
  private static final Pattern NUMBER_WORDS = Pattern.compile("(?i)\\b(twenty|thirty|forty|fifty|sixty|seventy|"
      + "eighty|ninety|hundreds?|thousands?|grand|millions?|billions?|dollars?|bucks|cents?|k)\\b");

  /**
   * Claim-like language a reply may not assert in free text: competing offers, legal conclusions,
   * eligibility or credit, commitments, promised outcomes, vehicle condition or history, market
   * comparisons, and statements attributed to the Dealer.
   */
  private static final Pattern CLAIMS = Pattern.compile("(?i)\\b("
      + "competing|competitors?|other dealers?|another dealer|other dealerships?|quote from|offers? from|elsewhere|cheaper|"
      + "carvana|carmax|illegal|unlawful|laws?|legal|legally|lawsuit|attorneys?|lawyers?|sue|sued|court|regulat\\w*|"
      + "violat\\w*|fraud\\w*|scams?|decepti\\w*|misleading|complaint|"
      + "qualif\\w*|pre-?approved|approved for|credit|eligib\\w*|"
      + "i will buy|i'll buy|we will buy|we'll buy|ready to (sign|buy)|i accept|we accept|i agree|we agree|commit\\w*|"
      + "promis\\w*|guarantee\\w*|risk-free|save|saves|saving|savings|"
      + "accidents?|damaged?|mechanic\\w*|inspect\\w*|title|history|carfax|autocheck|recalls?|warrant\\w*|flood|salvage|lemon|"
      + "market|invoice|msrp|overpriced|underpriced|average|typical|fair price|kbb|kelley|edmunds|"
      + "you said|you mentioned|you told|you stated|you promised|you agreed|you confirmed|you offered|"
      + "as discussed|as agreed|per our)\\b");

  private static final Pattern MARKUP = Pattern.compile("(?i)[<>{}\\[\\]\\\\]|https?:|www\\.|@|\\p{Cntrl}");

  private ReplyCheck() {}

  public static Checked check(ReplyGrounds grounds, Candidate candidate) {
    List<Statement> statements = new ArrayList<>();
    int omitted = 0;
    String opening = strip(candidate.opening());
    if (!opening.isEmpty()) {
      if (courtesy(opening)) statements.add(new Statement(Kind.OPENING, opening, null, null));
      else omitted++;
    }
    for (String id : new LinkedHashSet<>(candidate.factIds())) {
      ReplyGrounds.Fact fact = grounds.fact(id);
      if (fact == null) { omitted++; continue; }
      statements.add(new Statement(Kind.FACT, fact.statement(), fact.sourcePointer(), fact.sourceValue()));
    }
    Proposal proposal = proposal(grounds, candidate.proposedSellingPriceMinor());
    if (proposal != null) {
      statements.add(new Statement(Kind.PROPOSAL, GroundedReply.proposalSentence(proposal.sellingPriceMinor()),
          null, null));
    }
    for (String question : candidate.questions()) {
      String text = strip(question);
      if (text.isEmpty()) continue;
      if (text.length() <= MAX_QUESTION && text.endsWith("?") && text.indexOf('?') == text.length() - 1 && plain(text)) {
        statements.add(new Statement(Kind.QUESTION, text, null, null));
      } else omitted++;
    }
    String closing = strip(candidate.closing());
    if (!closing.isEmpty()) {
      if (courtesy(closing)) statements.add(new Statement(Kind.CLOSING, closing, null, null));
      else omitted++;
    }
    boolean substantive = statements.stream().anyMatch(statement ->
        statement.kind() == Kind.FACT || statement.kind() == Kind.PROPOSAL || statement.kind() == Kind.QUESTION);
    if (!substantive) throw new Rejected("NOTHING_GROUNDED");
    return new Checked(statements, omitted, proposal);
  }

  /** Whether free text is free of figures, claim-like language, links and markup. */
  public static boolean plain(String text) {
    return !FIGURES.matcher(text).find() && !NUMBER_WORDS.matcher(text).find()
        && !CLAIMS.matcher(text).find() && !MARKUP.matcher(text).find();
  }

  private static boolean courtesy(String text) {
    return text.length() <= MAX_COURTESY && !text.contains("?") && plain(text);
  }

  private static Proposal proposal(ReplyGrounds grounds, Long figure) {
    if (figure == null) return null;
    var boundary = grounds.boundary();
    if (boundary == null) throw new Rejected("PROPOSAL_WITHOUT_APPROVED_LIMIT");
    if (figure <= 0) throw new Rejected("PROPOSAL_NOT_POSITIVE");
    if (figure > boundary.ceilingMinor()) throw new Rejected("PROPOSAL_ABOVE_APPROVED_LIMIT");
    Long current = grounds.currentSellingPriceMinor();
    if (current != null && figure >= current) throw new Rejected("PROPOSAL_NOT_BELOW_DEALER_OFFER");
    return new Proposal(boundary.currency(), figure, boundary.ceilingMinor(), boundary.sellingWalkAwayMinor(),
        boundary.outTheDoorWalkAwayMinor());
  }

  private static String strip(String text) {
    return text == null ? "" : text.strip();
  }
}

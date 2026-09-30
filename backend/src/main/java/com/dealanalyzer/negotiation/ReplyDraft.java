package com.dealanalyzer.negotiation;

import com.dealanalyzer.ai.ModelProvenance;
import java.util.List;

/**
 * A reply suggestion for the Buyer to edit and send themselves. It is returned, never stored, and
 * carries nothing that can change a calculation, approval, limit or review state.
 *
 * @param path which producer made the text; {@code TEMPLATE} whenever the model path is off,
 *     unavailable, or its output was rejected
 * @param fallbackReason why the template was used, or null for a model reply
 * @param omittedStatements model statements dropped because they could not be grounded
 * @param provenance the model versions behind a model reply, or null for a template reply
 */
public record ReplyDraft(
    String policyVersion,
    Path path,
    String fallbackReason,
    int reportVersion,
    String dealerOfferId,
    Integer dealerOfferVersion,
    List<Statement> statements,
    String draft,
    int omittedStatements,
    Proposal proposal,
    ModelProvenance provenance,
    String scope) {
  public ReplyDraft { statements = List.copyOf(statements); }

  public enum Path { MODEL, TEMPLATE }

  public enum Kind { OPENING, FACT, PROPOSAL, QUESTION, CLOSING }

  /** One sentence of the reply; a FACT names the report pointer and value it restates. */
  public record Statement(Kind kind, String text, String sourcePointer, String sourceValue) {}

  /** A counter selling price and the approved limits it was checked against. */
  public record Proposal(String currency, long sellingPriceMinor, long ceilingMinor, long sellingWalkAwayMinor,
      Long outTheDoorWalkAwayMinor) {}
}

package com.dealanalyzer.relay;

/**
 * The record an approved dealer message was prepared for: a quote request, counteroffer, acceptance or test-drive
 * message. Every outbound message has exactly one owner. When the Buyer marks a handed-off message sent, or
 * discards it, {@link DealerMessageHandoff} goes through its owner, so the record reaches the same state as a
 * first send exactly once. Every method runs inside the hand-off transaction while the case lock is held.
 */
public interface RelayMessageOwner {
  /** True when this owner's record points at the message. */
  boolean owns(String caseId, String relayMessageId);

  /**
   * Throws {@link IllegalArgumentException} with a Buyer-readable reason when the message may no longer be marked
   * sent: the mandate, policy decision, negotiation state or record changed.
   */
  void requireResendPermitted(String caseId, String token, String relayMessageId);

  /**
   * The Buyer sent the message. Applies the first-send state change, including negotiation transitions, unless
   * the record already reached it. Returns work to run after the transaction commits, or null.
   */
  Runnable sent(String caseId, String token, String relayMessageId);

  /** The Buyer discarded the handed-off message ({@code DISCARDED}); nothing reached the dealer. */
  void unsent(String caseId, String relayMessageId, String state);
}

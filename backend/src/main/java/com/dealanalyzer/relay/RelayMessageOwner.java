package com.dealanalyzer.relay;

/**
 * The record that sent an outbound relay message: a quote request, counteroffer, acceptance or test-drive
 * message. Every outbound message has at most one owner. Settling or retrying an unclear send goes through
 * it, so its record reaches the same state as a first-time successful send, exactly once. Every method runs
 * inside the relay's transaction while the case lock is held.
 */
public interface RelayMessageOwner {
  /** True when this owner's record points at the relay message. */
  boolean owns(String caseId, String relayMessageId);

  /**
   * Throws {@link IllegalArgumentException} with a Buyer-readable reason when the original send would no
   * longer be permitted: the mandate, policy decision, negotiation state or record changed.
   */
  void requireResendPermitted(String caseId, String token, String relayMessageId);

  /**
   * The provider has the message. Applies the first-send state change, including negotiation transitions,
   * unless the record already reached it. Returns work to run after the transaction commits, or null.
   */
  Runnable sent(String caseId, String token, String relayMessageId);

  /** The send is still unclear ({@code DELIVERY_UNKNOWN}) or the provider does not have it ({@code FAILED}). */
  void unsent(String caseId, String relayMessageId, String state);
}

package com.dealanalyzer.recovery;
/** External delivery boundary for single-use access links. Implementations must never log the link or recipient. */
public interface RecoveryEmail {
  enum Purpose { CASE_RECOVERY, ACCOUNT_SIGN_IN, DEALER_REPLY, DRAFT_NEEDS_APPROVAL, MANDATE_EXPIRING, APPOINTMENT, ACCOUNT_SECURITY }
  void send(String recipient, String link);
  default void send(String recipient, String link, Purpose purpose) { send(recipient, link); }
  default void send(String recipient, String link, Purpose purpose, String language) { send(recipient, link, purpose); }
}

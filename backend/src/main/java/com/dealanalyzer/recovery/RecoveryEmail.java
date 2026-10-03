package com.dealanalyzer.recovery;
/** External delivery boundary for single-use access links. Implementations must never log the link or recipient. */
public interface RecoveryEmail {
  enum Purpose { CASE_RECOVERY, ACCOUNT_SIGN_IN }
  void send(String recipient, String link);
  default void send(String recipient, String link, Purpose purpose) { send(recipient, link); }
}

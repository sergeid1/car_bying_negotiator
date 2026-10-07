package com.dealanalyzer.mailbox;

import org.springframework.http.HttpStatus;

/**
 * A reviewed, Buyer-facing mailbox outcome with a stable code. Its detail never contains Buyer input, addresses,
 * tokens or provider payloads. {@code ownEmailApp} marks a send refusal whose message is still available on the
 * own-email-app path.
 */
public class MailboxProblem extends RuntimeException {
  final HttpStatus status;
  final String code;
  final boolean ownEmailApp;

  MailboxProblem(HttpStatus status, String code, String detail) { this(status, code, detail, false); }

  MailboxProblem(HttpStatus status, String code, String detail, boolean ownEmailApp) {
    super(detail, null, false, false);
    this.status = status; this.code = code; this.ownEmailApp = ownEmailApp;
  }

  public String code() { return code; }
}

package com.dealanalyzer.account;

import org.springframework.http.HttpStatus;

/** A reviewed, Buyer-facing account outcome. Its detail never contains Buyer input. */
class AccountProblem extends RuntimeException {
  final HttpStatus status;
  final String code;

  AccountProblem(HttpStatus status, String code, String detail) {
    super(detail, null, false, false);
    this.status = status; this.code = code;
  }
}

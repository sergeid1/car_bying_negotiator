package com.dealanalyzer.desk;

import org.springframework.http.HttpStatus;

/** A reviewed, Buyer-facing desk-start outcome; none of the messages echoes what the Buyer entered. */
class DeskStartProblem extends RuntimeException {
  final HttpStatus status;
  final String code;
  final String field;

  DeskStartProblem(HttpStatus status, String code, String detail, String field) {
    super(detail, null, false, false);
    this.status = status; this.code = code; this.field = field;
  }

  static DeskStartProblem invalid(String field, String detail) {
    return new DeskStartProblem(HttpStatus.BAD_REQUEST, "DESK_START_INVALID", detail, field);
  }
}

package com.dealanalyzer.listing.capture;

import org.springframework.http.HttpStatus;

/** A Buyer-facing listing capture outcome, in authored words that never echo what the Buyer entered. */
class ListingCaptureProblem extends RuntimeException {
  final HttpStatus status;
  final String code;
  final String field;

  ListingCaptureProblem(HttpStatus status, String code, String detail, String field) {
    super(detail, null, false, false);
    this.status = status; this.code = code; this.field = field;
  }

  static ListingCaptureProblem invalid(String field, String detail) {
    return new ListingCaptureProblem(HttpStatus.BAD_REQUEST, "INVALID_LISTING_CAPTURE", detail, field);
  }
}

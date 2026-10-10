package com.dealanalyzer.trade;

import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * A reviewed, Buyer-facing trade-in outcome. Field errors name the field and the correction in
 * authored words; none of them echoes what the Buyer entered.
 */
class TradeProblem extends RuntimeException {
  record FieldError(String field, String message) {}

  final HttpStatus status;
  final String code;
  final List<FieldError> errors;

  TradeProblem(HttpStatus status, String code, String detail, List<FieldError> errors) {
    super(detail, null, false, false);
    this.status = status; this.code = code; this.errors = List.copyOf(errors);
  }

  static TradeProblem notRecorded() {
    return new TradeProblem(HttpStatus.CONFLICT, "TRADE_NOT_RECORDED",
        "Record the car you are trading in before adding a written offer for it.", List.of());
  }

  static TradeProblem changed() {
    return new TradeProblem(HttpStatus.CONFLICT, "TRADE_CHANGED", "Your trade-in changed since you opened it. "
        + "Reload it, check the latest details and save your edit again.", List.of());
  }
}

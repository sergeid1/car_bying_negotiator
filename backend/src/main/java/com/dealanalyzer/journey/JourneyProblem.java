package com.dealanalyzer.journey;

import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * A reviewed, Buyer-facing journey outcome. Field errors name the field and the correction in
 * authored words; none of them echoes what the Buyer entered.
 */
class JourneyProblem extends RuntimeException {
  record FieldError(String field, String message, String reason, List<String> candidateJurisdictions) {
    FieldError(String field, String message) { this(field, message, null, List.of()); }
  }

  final HttpStatus status;
  final String code;
  final List<FieldError> errors;

  JourneyProblem(HttpStatus status, String code, String detail, List<FieldError> errors) {
    super(detail, null, false, false);
    this.status = status; this.code = code; this.errors = List.copyOf(errors);
  }

  static JourneyProblem notFound() {
    return new JourneyProblem(HttpStatus.NOT_FOUND, "JOURNEY_NOT_FOUND",
        "That Buying Journey does not exist in this account. It may have been deleted.", List.of());
  }
}

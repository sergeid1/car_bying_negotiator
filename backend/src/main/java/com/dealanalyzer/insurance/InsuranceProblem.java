package com.dealanalyzer.insurance;

import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * A reviewed, Buyer-facing insurance outcome. Field errors name the field and the correction in authored
 * words; none of them echoes what the Buyer entered, so no application detail reaches a response or a log.
 */
class InsuranceProblem extends RuntimeException {
  record FieldError(String field, String message) {}

  final HttpStatus status;
  final String code;
  final List<FieldError> errors;

  InsuranceProblem(HttpStatus status, String code, String detail, List<FieldError> errors) {
    super(detail, null, false, false);
    this.status = status; this.code = code; this.errors = List.copyOf(errors);
  }

  static InsuranceProblem invalid(List<FieldError> errors) {
    return new InsuranceProblem(HttpStatus.BAD_REQUEST, "INSURANCE_INVALID", "Check the highlighted insurance details.", errors);
  }

  static InsuranceProblem noApplication() {
    return new InsuranceProblem(HttpStatus.CONFLICT, "INSURANCE_APPLICATION_REQUIRED",
        "Fill in your insurance application before getting or adding quotes.", List.of());
  }

  static InsuranceProblem changed() {
    return new InsuranceProblem(HttpStatus.CONFLICT, "INSURANCE_APPLICATION_CHANGED", "Your insurance application changed "
        + "since you opened it. Reload it, check the latest details and try again.", List.of());
  }

  static InsuranceProblem consentRequired() {
    return new InsuranceProblem(HttpStatus.BAD_REQUEST, "INSURANCE_CONSENT_REQUIRED", "Nothing was sent. Read the partner's "
        + "consent as it is shown now and agree to it before getting quotes.", List.of());
  }

  static InsuranceProblem keyRequired() {
    return new InsuranceProblem(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
        "Retry the request; it was missing its request key.", List.of());
  }
}

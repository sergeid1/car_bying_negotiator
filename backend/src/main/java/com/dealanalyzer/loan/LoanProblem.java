package com.dealanalyzer.loan;

import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * A reviewed, Buyer-facing loan outcome. Field errors name the field and the correction in authored words; none
 * of them echoes what the Buyer entered, so no application detail reaches a response or a log.
 */
class LoanProblem extends RuntimeException {
  record FieldError(String field, String message) {}

  final HttpStatus status;
  final String code;
  final List<FieldError> errors;

  LoanProblem(HttpStatus status, String code, String detail, List<FieldError> errors) {
    super(detail, null, false, false);
    this.status = status; this.code = code; this.errors = List.copyOf(errors);
  }

  static LoanProblem invalid(List<FieldError> errors) {
    return new LoanProblem(HttpStatus.BAD_REQUEST, "LOAN_INVALID", "Check the highlighted loan details.", errors);
  }

  static LoanProblem noApplication() {
    return new LoanProblem(HttpStatus.CONFLICT, "LOAN_APPLICATION_REQUIRED",
        "Fill in your loan application before getting or adding pre-approvals.", List.of());
  }

  static LoanProblem changed() {
    return new LoanProblem(HttpStatus.CONFLICT, "LOAN_APPLICATION_CHANGED", "Your loan application changed since you "
        + "opened it. Reload it, check the latest details and try again.", List.of());
  }

  static LoanProblem authorizationRequired() {
    return new LoanProblem(HttpStatus.BAD_REQUEST, "LOAN_AUTHORIZATION_REQUIRED", "Nothing was sent. Read the credit "
        + "authorization as it is shown now and agree to it before getting pre-approvals.", List.of());
  }

  static LoanProblem notChoosable() {
    return new LoanProblem(HttpStatus.CONFLICT, "LOAN_OFFER_NOT_CHOOSABLE", "Only an offer that has not expired can be "
        + "used as your own financing. Choose another offer or add one from your lender.", List.of());
  }

  static LoanProblem keyRequired() {
    return new LoanProblem(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
        "Retry the request; it was missing its request key.", List.of());
  }
}

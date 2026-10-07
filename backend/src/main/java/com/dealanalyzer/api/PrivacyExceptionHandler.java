package com.dealanalyzer.api;

import java.net.URI;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Framework parse/validation errors and unexpected exceptions must not echo or log Buyer values. */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
class PrivacyExceptionHandler extends ResponseEntityExceptionHandler {
  @Override protected ResponseEntity<Object> handleExceptionInternal(Exception error, Object body,
      HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    return new ResponseEntity<>(safe(status), headers, status);
  }
  @ExceptionHandler(Exception.class)
  ResponseEntity<Object> unexpected(Exception error) {
    HttpStatusCode status = HttpStatus.INTERNAL_SERVER_ERROR;
    if (error instanceof ResponseStatusException response) status = response.getStatusCode();
    else {
      var annotation = AnnotatedElementUtils.findMergedAnnotation(error.getClass(), ResponseStatus.class);
      if (annotation != null) status = annotation.code();
    }
    var problem = safe(status);
    // Only exact, reviewed product instructions may survive. Never forward arbitrary exception text.
    if (error instanceof com.dealanalyzer.verification.InvalidDealTermsVerificationException
        || error instanceof com.dealanalyzer.economics.InvalidCashEconomicsException) {
      String instruction = error.getMessage();
      if (instruction != null && SAFE_INSTRUCTIONS.contains(instruction)) problem.setDetail(instruction);
    }
    return new ResponseEntity<>(problem, status);
  }
  private static final java.util.Set<String> SAFE_INSTRUCTIONS = java.util.Set.of(
      "Confirm every financially material and low-confidence value.",
      "Confirm every financially material line item.",
      "Correct missing material values before verification.",
      "Resolve the Vehicle identity conflict before verifying the Dealer Offer.",
      "Resolve the document and Buyer-confirmed Vehicle VIN conflict before verification.",
      "Cash economics require a current verified Dealer Offer",
      "A correction target is required.",
      "Candidate corrections require a normalized value and unit only.",
      "A correction cannot change the candidate value unit.",
      "Line-item corrections require a category and amount only.",
      "The corrected line-item amount is outside supported bounds.");
  private static ProblemDetail safe(HttpStatusCode status) {
    var problem = ProblemDetail.forStatusAndDetail(status,
        status.is5xxServerError() ? "The operation could not be completed." : "The request could not be processed.");
    // Framework 7 renamed 422's reason phrase; preserve the existing API error body.
    if (status.value() == 422) problem.setTitle("Unprocessable Entity");
    problem.setProperty("failureCategory", status.is5xxServerError() ? "INTERNAL" : "BUYER_INPUT");
    problem.setInstance(URI.create("/api"));
    return problem;
  }
}

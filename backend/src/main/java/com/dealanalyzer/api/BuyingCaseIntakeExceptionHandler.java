package com.dealanalyzer.api;

import com.dealanalyzer.buyingcase.InvalidBuyingCaseIntakeException;
import com.dealanalyzer.buyingcase.UnsupportedLocationException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Intake failures name the field the Buyer must correct. Their messages are authored constants,
 * never submitted values, so returning them cannot disclose what the Buyer entered.
 */
@Order(0)
@RestControllerAdvice
class BuyingCaseIntakeExceptionHandler {
  @ExceptionHandler(InvalidBuyingCaseIntakeException.class)
  ProblemDetail invalidIntake(InvalidBuyingCaseIntakeException exception) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    problem.setTitle("The Buying Case details need a correction");
    problem.setProperty("field", exception.field());
    problem.setProperty("failureCategory", "BUYER_INPUT");
    return problem;
  }

  @ExceptionHandler(UnsupportedLocationException.class)
  ProblemDetail unsupportedLocation(UnsupportedLocationException exception) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage());
    problem.setTitle("That location cannot be served");
    problem.setProperty("field", exception.field());
    problem.setProperty("reason", exception.reason().name());
    problem.setProperty("candidateJurisdictions", exception.candidates());
    problem.setProperty("failureCategory", "BUYER_INPUT");
    return problem;
  }
}

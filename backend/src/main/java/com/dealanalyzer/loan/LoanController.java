package com.dealanalyzer.loan;

import com.dealanalyzer.account.AccountSession;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

/**
 * The Buyer's loan application and pre-approvals, from a Buying Case or from a Buying Journey on its own (spec 5.10).
 * Case routes are authorized by the case credential and use the journey's application when the case is in one;
 * journey routes are authorized by the Buyer Account session. Every parameter is optional at binding and checked
 * after authorization.
 */
@RestController
public class LoanController {
  private static final String CASE = "/api/v1/cases/{caseId}/loan";
  private static final String JOURNEY = "/api/v1/journeys/{journeyId}/loan";
  private final LoanShoppingService loans;

  LoanController(LoanShoppingService loans) { this.loans = loans; }

  @GetMapping(CASE)
  public LoanShoppingService.View caseView(@PathVariable String caseId,
      @RequestHeader(value = "Case-Access-Token", required = false) String token) {
    return loans.view(loans.forCase(caseId, token));
  }

  @PutMapping(CASE)
  public LoanShoppingService.View caseStore(@PathVariable String caseId,
      @RequestHeader(value = "Case-Access-Token", required = false) String token,
      @RequestBody(required = false) LoanShoppingService.Input input) {
    return loans.store(loans.forCase(caseId, token), input);
  }

  @DeleteMapping(CASE)
  public LoanShoppingService.View caseRemove(@PathVariable String caseId,
      @RequestHeader(value = "Case-Access-Token", required = false) String token) {
    return loans.remove(loans.forCase(caseId, token));
  }

  @PostMapping(CASE + "/pre-approval-requests") @ResponseStatus(HttpStatus.CREATED)
  public LoanShoppingService.View caseRequest(@PathVariable String caseId,
      @RequestHeader(value = "Case-Access-Token", required = false) String token,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody(required = false) LoanShoppingService.RequestInput input) {
    return loans.requestPreApprovals(loans.forCase(caseId, token), key, input);
  }

  @PostMapping(CASE + "/own-offers") @ResponseStatus(HttpStatus.CREATED)
  public LoanShoppingService.View caseAddOwn(@PathVariable String caseId,
      @RequestHeader(value = "Case-Access-Token", required = false) String token,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody(required = false) LoanShoppingService.OwnOfferInput input) {
    return loans.addOwnOffer(loans.forCase(caseId, token), key, input);
  }

  @DeleteMapping(CASE + "/own-offers/{offerId}")
  public LoanShoppingService.View caseRemoveOwn(@PathVariable String caseId, @PathVariable String offerId,
      @RequestHeader(value = "Case-Access-Token", required = false) String token) {
    return loans.removeOwnOffer(loans.forCase(caseId, token), offerId);
  }

  @PutMapping(CASE + "/chosen-offer")
  public LoanShoppingService.View caseChoose(@PathVariable String caseId,
      @RequestHeader(value = "Case-Access-Token", required = false) String token,
      @RequestBody(required = false) LoanShoppingService.ChoiceInput input) {
    return loans.choose(loans.forCase(caseId, token), input);
  }

  @DeleteMapping(CASE + "/chosen-offer")
  public LoanShoppingService.View caseClearChoice(@PathVariable String caseId,
      @RequestHeader(value = "Case-Access-Token", required = false) String token) {
    return loans.clearChoice(loans.forCase(caseId, token));
  }

  @GetMapping(JOURNEY)
  public LoanShoppingService.View journeyView(@AccountSession String session, @PathVariable String journeyId) {
    return loans.view(loans.forJourney(session, journeyId));
  }

  @PutMapping(JOURNEY)
  public LoanShoppingService.View journeyStore(@AccountSession String session, @PathVariable String journeyId,
      @RequestBody(required = false) LoanShoppingService.Input input) {
    return loans.store(loans.forJourney(session, journeyId), input);
  }

  @DeleteMapping(JOURNEY)
  public LoanShoppingService.View journeyRemove(@AccountSession String session, @PathVariable String journeyId) {
    return loans.remove(loans.forJourney(session, journeyId));
  }

  @PostMapping(JOURNEY + "/pre-approval-requests") @ResponseStatus(HttpStatus.CREATED)
  public LoanShoppingService.View journeyRequest(@AccountSession String session, @PathVariable String journeyId,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody(required = false) LoanShoppingService.RequestInput input) {
    return loans.requestPreApprovals(loans.forJourney(session, journeyId), key, input);
  }

  @PostMapping(JOURNEY + "/own-offers") @ResponseStatus(HttpStatus.CREATED)
  public LoanShoppingService.View journeyAddOwn(@AccountSession String session, @PathVariable String journeyId,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody(required = false) LoanShoppingService.OwnOfferInput input) {
    return loans.addOwnOffer(loans.forJourney(session, journeyId), key, input);
  }

  @DeleteMapping(JOURNEY + "/own-offers/{offerId}")
  public LoanShoppingService.View journeyRemoveOwn(@AccountSession String session, @PathVariable String journeyId,
      @PathVariable String offerId) {
    return loans.removeOwnOffer(loans.forJourney(session, journeyId), offerId);
  }

  @PutMapping(JOURNEY + "/chosen-offer")
  public LoanShoppingService.View journeyChoose(@AccountSession String session, @PathVariable String journeyId,
      @RequestBody(required = false) LoanShoppingService.ChoiceInput input) {
    return loans.choose(loans.forJourney(session, journeyId), input);
  }

  @DeleteMapping(JOURNEY + "/chosen-offer")
  public LoanShoppingService.View journeyClearChoice(@AccountSession String session, @PathVariable String journeyId) {
    return loans.clearChoice(loans.forJourney(session, journeyId));
  }

  /** Loan outcomes keep their code, fixed detail and field errors; nothing echoes the application. */
  @RestControllerAdvice(assignableTypes = LoanController.class)
  @Order(Ordered.HIGHEST_PRECEDENCE)
  static class Problems {
    @ExceptionHandler(LoanProblem.class)
    ResponseEntity<ProblemDetail> problem(LoanProblem problem) {
      var body = ProblemDetail.forStatusAndDetail(problem.status, problem.getMessage());
      body.setProperty("code", problem.code);
      body.setProperty("errors", problem.errors);
      return ResponseEntity.status(problem.status).body(body);
    }

    /** Handled here so the parser's message, which can quote the input, is neither returned nor logged. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException ignored) {
      var body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
          "The loan details could not be read. Amounts, terms and mileage must be numbers.");
      body.setProperty("code", "LOAN_UNREADABLE");
      body.setProperty("errors", List.of());
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
  }
}

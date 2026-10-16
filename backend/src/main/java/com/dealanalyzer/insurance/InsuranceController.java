package com.dealanalyzer.insurance;

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
 * The Buyer's insurance application and quotes, from a Buying Case or from a Buying Journey on its own (INS-01).
 * Case routes are authorized by the case credential and use the journey's application when the case is in one;
 * journey routes are authorized by the Buyer Account session. Every parameter is optional at binding and checked
 * after authorization.
 */
@RestController
public class InsuranceController {
  private static final String CASE = "/api/v1/cases/{caseId}/insurance";
  private static final String JOURNEY = "/api/v1/journeys/{journeyId}/insurance";
  private final InsuranceShoppingService insurance;

  InsuranceController(InsuranceShoppingService insurance) { this.insurance = insurance; }

  @GetMapping(CASE)
  public InsuranceShoppingService.View caseView(@PathVariable String caseId,
      @RequestHeader(value = "Case-Access-Token", required = false) String token) {
    return insurance.view(insurance.forCase(caseId, token));
  }

  @PutMapping(CASE)
  public InsuranceShoppingService.View caseStore(@PathVariable String caseId,
      @RequestHeader(value = "Case-Access-Token", required = false) String token,
      @RequestBody(required = false) InsuranceShoppingService.Input input) {
    return insurance.store(insurance.forCase(caseId, token), input);
  }

  @DeleteMapping(CASE)
  public InsuranceShoppingService.View caseRemove(@PathVariable String caseId,
      @RequestHeader(value = "Case-Access-Token", required = false) String token) {
    return insurance.remove(insurance.forCase(caseId, token));
  }

  @PostMapping(CASE + "/quote-requests") @ResponseStatus(HttpStatus.CREATED)
  public InsuranceShoppingService.View caseRequest(@PathVariable String caseId,
      @RequestHeader(value = "Case-Access-Token", required = false) String token,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody(required = false) InsuranceShoppingService.RequestInput input) {
    return insurance.requestQuotes(insurance.forCase(caseId, token), key, input);
  }

  @PostMapping(CASE + "/own-quotes") @ResponseStatus(HttpStatus.CREATED)
  public InsuranceShoppingService.View caseAddOwn(@PathVariable String caseId,
      @RequestHeader(value = "Case-Access-Token", required = false) String token,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody(required = false) InsuranceShoppingService.OwnQuoteInput input) {
    return insurance.addOwnQuote(insurance.forCase(caseId, token), key, input);
  }

  @DeleteMapping(CASE + "/own-quotes/{quoteId}")
  public InsuranceShoppingService.View caseRemoveOwn(@PathVariable String caseId, @PathVariable String quoteId,
      @RequestHeader(value = "Case-Access-Token", required = false) String token) {
    return insurance.removeOwnQuote(insurance.forCase(caseId, token), quoteId);
  }

  @GetMapping(JOURNEY)
  public InsuranceShoppingService.View journeyView(@AccountSession String session, @PathVariable String journeyId) {
    return insurance.view(insurance.forJourney(session, journeyId));
  }

  @PutMapping(JOURNEY)
  public InsuranceShoppingService.View journeyStore(@AccountSession String session, @PathVariable String journeyId,
      @RequestBody(required = false) InsuranceShoppingService.Input input) {
    return insurance.store(insurance.forJourney(session, journeyId), input);
  }

  @DeleteMapping(JOURNEY)
  public InsuranceShoppingService.View journeyRemove(@AccountSession String session, @PathVariable String journeyId) {
    return insurance.remove(insurance.forJourney(session, journeyId));
  }

  @PostMapping(JOURNEY + "/quote-requests") @ResponseStatus(HttpStatus.CREATED)
  public InsuranceShoppingService.View journeyRequest(@AccountSession String session, @PathVariable String journeyId,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody(required = false) InsuranceShoppingService.RequestInput input) {
    return insurance.requestQuotes(insurance.forJourney(session, journeyId), key, input);
  }

  @PostMapping(JOURNEY + "/own-quotes") @ResponseStatus(HttpStatus.CREATED)
  public InsuranceShoppingService.View journeyAddOwn(@AccountSession String session, @PathVariable String journeyId,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody(required = false) InsuranceShoppingService.OwnQuoteInput input) {
    return insurance.addOwnQuote(insurance.forJourney(session, journeyId), key, input);
  }

  @DeleteMapping(JOURNEY + "/own-quotes/{quoteId}")
  public InsuranceShoppingService.View journeyRemoveOwn(@AccountSession String session, @PathVariable String journeyId,
      @PathVariable String quoteId) {
    return insurance.removeOwnQuote(insurance.forJourney(session, journeyId), quoteId);
  }

  /** Insurance outcomes keep their code, fixed detail and field errors; nothing echoes the application. */
  @RestControllerAdvice(assignableTypes = InsuranceController.class)
  @Order(Ordered.HIGHEST_PRECEDENCE)
  static class Problems {
    @ExceptionHandler(InsuranceProblem.class)
    ResponseEntity<ProblemDetail> problem(InsuranceProblem problem) {
      var body = ProblemDetail.forStatusAndDetail(problem.status, problem.getMessage());
      body.setProperty("code", problem.code);
      body.setProperty("errors", problem.errors);
      return ResponseEntity.status(problem.status).body(body);
    }

    /** Handled here so the parser's message, which can quote the input, is neither returned nor logged. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException ignored) {
      var body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
          "The insurance details could not be read. Ages, counts, mileage and amounts must be whole numbers.");
      body.setProperty("code", "INSURANCE_UNREADABLE");
      body.setProperty("errors", List.of());
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
  }
}

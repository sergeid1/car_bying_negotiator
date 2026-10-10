package com.dealanalyzer.trade;

import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * The Buyer's trade-in for one case: the journey's Trade Vehicle when the case is in a journey,
 * otherwise the case's own. Every endpoint is authorized by the case credential before anything
 * else is read, so every parameter is optional at binding and checked afterwards.
 */
@RestController @RequestMapping("/api/v1/cases/{caseId}/trade")
public class TradeController {
  private final TradeService trades;

  TradeController(TradeService trades) { this.trades = trades; }

  public record CarryOver(String sourceCaseId) {}

  @GetMapping
  public TradeService.View view(@PathVariable String caseId,
      @RequestHeader(value = "Case-Access-Token", required = false) String token) {
    return trades.view(caseId, token);
  }

  @PutMapping
  public TradeService.View save(@PathVariable String caseId,
      @RequestHeader(value = "Case-Access-Token", required = false) String token,
      @RequestBody(required = false) TradeService.Input input) {
    return trades.save(caseId, token, input);
  }

  @DeleteMapping
  public TradeService.View remove(@PathVariable String caseId,
      @RequestHeader(value = "Case-Access-Token", required = false) String token) {
    return trades.remove(caseId, token);
  }

  @PostMapping(value = "/offers", consumes = "multipart/form-data") @ResponseStatus(HttpStatus.CREATED)
  public TradeService.View addOffer(@PathVariable String caseId,
      @RequestHeader(value = "Case-Access-Token", required = false) String token,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestParam(required = false) String amountMinor, @RequestParam(required = false) String source,
      @RequestParam(required = false) String offerDate, @RequestParam(required = false) String expiresOn,
      @RequestPart(value = "file", required = false) MultipartFile file) {
    return trades.addOffer(caseId, token, key, new TradeService.OfferInput(amountMinor, source, offerDate, expiresOn), file);
  }

  @DeleteMapping("/offers/{offerId}")
  public TradeService.View removeOffer(@PathVariable String caseId, @PathVariable String offerId,
      @RequestHeader(value = "Case-Access-Token", required = false) String token) {
    return trades.removeOffer(caseId, token, offerId);
  }

  /** A trade value the Buyer looked up themselves; nothing is fetched from the site they name. */
  @PostMapping("/estimates") @ResponseStatus(HttpStatus.CREATED)
  public TradeService.View addEstimate(@PathVariable String caseId,
      @RequestHeader(value = "Case-Access-Token", required = false) String token,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody(required = false) TradeService.EstimateInput input) {
    return trades.addEstimate(caseId, token, key, input);
  }

  @DeleteMapping("/estimates/{estimateId}")
  public TradeService.View removeEstimate(@PathVariable String caseId, @PathVariable String estimateId,
      @RequestHeader(value = "Case-Access-Token", required = false) String token) {
    return trades.removeEstimate(caseId, token, estimateId);
  }

  @GetMapping("/offers/{offerId}/pages/{pageNumber}/image")
  public ResponseEntity<byte[]> evidencePage(@PathVariable String caseId, @PathVariable String offerId,
      @PathVariable int pageNumber, @RequestHeader(value = "Case-Access-Token", required = false) String token) {
    return ResponseEntity.ok().header("Content-Type", "image/png").header("X-Content-Type-Options", "nosniff")
        .header("Cache-Control", "no-store").body(trades.evidencePage(caseId, token, offerId, pageNumber));
  }

  /** Carries the earlier case's trade into a case started at the Desk Check; both case credentials are required. */
  @PostMapping("/carry-over")
  public TradeService.View carryOver(@PathVariable String caseId,
      @RequestHeader(value = "Case-Access-Token", required = false) String token,
      @RequestHeader(value = "Source-Case-Access-Token", required = false) String sourceToken,
      @RequestBody(required = false) CarryOver input) {
    return trades.carryOver(caseId, token, input == null ? null : input.sourceCaseId(), sourceToken);
  }

  /** Trade outcomes keep their code, fixed detail and field errors; nothing echoes Buyer input. */
  @RestControllerAdvice(assignableTypes = TradeController.class)
  @Order(Ordered.HIGHEST_PRECEDENCE)
  static class Problems {
    @ExceptionHandler(TradeProblem.class)
    ResponseEntity<ProblemDetail> problem(TradeProblem problem) {
      var body = ProblemDetail.forStatusAndDetail(problem.status, problem.getMessage());
      body.setProperty("code", problem.code);
      body.setProperty("errors", problem.errors);
      return ResponseEntity.status(problem.status).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException ignored) {
      var body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
          "The trade-in could not be read. Mileage and the payoff amount must be whole numbers.");
      body.setProperty("code", "TRADE_UNREADABLE");
      body.setProperty("errors", List.of());
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
  }
}

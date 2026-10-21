package com.dealanalyzer.quote;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/v1/cases/{caseId}/quote-requests")
public class QuoteRequestController {
  private final QuoteRequestService quotes;
  public QuoteRequestController(QuoteRequestService quotes) { this.quotes = quotes; }
  @GetMapping
  public QuoteRequestService.View view(@PathVariable String caseId, @RequestHeader("Case-Access-Token") String token) {
    return quotes.view(caseId, token);
  }
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public QuoteRequestService.Quote create(@PathVariable String caseId, @RequestHeader("Case-Access-Token") String token,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String key, @RequestBody QuoteRequestService.Create request) {
    return quotes.create(caseId, token, key, request);
  }
  @PostMapping("/{id}/approval")
  public QuoteRequestService.Quote approve(@PathVariable String caseId, @PathVariable String id,
      @RequestHeader("Case-Access-Token") String token, @RequestBody QuoteRequestService.Approval approval) {
    return quotes.approveAndSend(caseId, token, id, approval);
  }
  /** The language of one dealer's emails (ticket 149); requests awaiting approval are prepared again in it. */
  @PutMapping("/dealers/{dealerId}/email-language")
  public QuoteRequestService.View changeLanguage(@PathVariable String caseId, @PathVariable String dealerId,
      @RequestHeader("Case-Access-Token") String token, @RequestBody QuoteRequestService.LanguageChoice choice) {
    return quotes.changeLanguage(caseId, token, dealerId, choice);
  }
  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail invalid(IllegalArgumentException failure) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, failure.getMessage());
  }
}

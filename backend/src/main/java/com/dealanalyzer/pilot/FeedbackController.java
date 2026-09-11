package com.dealanalyzer.pilot;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/cases/{caseId}/feedback")
class FeedbackController {
  private final FeedbackService feedback;
  FeedbackController(FeedbackService feedback) { this.feedback = feedback; }
  @PostMapping("/findings")
  Object rate(@PathVariable String caseId, @RequestHeader("Case-Access-Token") String token,
      @Valid @RequestBody FeedbackService.FindingRequest request) { return feedback.rate(caseId, token, request); }
  @PostMapping("/outcomes")
  Object outcome(@PathVariable String caseId, @RequestHeader("Case-Access-Token") String token,
      @Valid @RequestBody FeedbackService.OutcomeRequest request) { return feedback.outcome(caseId, token, request); }
  @GetMapping
  Object history(@PathVariable String caseId, @RequestHeader("Case-Access-Token") String token) {
    return feedback.history(caseId, token);
  }
}

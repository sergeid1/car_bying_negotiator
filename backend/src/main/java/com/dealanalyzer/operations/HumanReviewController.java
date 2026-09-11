package com.dealanalyzer.operations;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
class HumanReviewController {
  private final HumanReviewService reviews;
  HumanReviewController(HumanReviewService reviews) { this.reviews = reviews; }
  record Request(@NotBlank String extractionId, @NotBlank String candidateId) {}
  record Completion(@NotNull HumanReviewService.Outcome outcome) {}
  @PostMapping("/api/v1/cases/{caseId}/human-reviews")
  Map<String, Object> request(@PathVariable String caseId, @RequestHeader("Case-Access-Token") String token, @Valid @RequestBody Request request) {
    return reviews.request(caseId, token, request.extractionId(), request.candidateId());
  }
  @GetMapping("/api/v1/cases/{caseId}/human-reviews")
  List<Map<String, Object>> history(@PathVariable String caseId, @RequestHeader("Case-Access-Token") String token) {
    return reviews.history(caseId, token);
  }
  @GetMapping("/api/v1/operator/reviews")
  List<Map<String, Object>> queue(@RequestAttribute("operator") OperatorAccounts.Principal operator) {
    OperatorController.require(operator, OperatorAccounts.Role.REVIEWER);
    return reviews.queue();
  }
  @PostMapping("/api/v1/operator/reviews/{id}/start")
  Map<String, Object> start(@PathVariable String id, @RequestAttribute("operator") OperatorAccounts.Principal operator) {
    OperatorController.require(operator, OperatorAccounts.Role.REVIEWER);
    return reviews.start(id, operator.id());
  }
  @GetMapping("/api/v1/operator/reviews/{id}/content")
  Map<String, Object> content(@PathVariable String id, @RequestAttribute("operator") OperatorAccounts.Principal operator) {
    OperatorController.require(operator, OperatorAccounts.Role.REVIEWER);
    return reviews.content(id, operator.id());
  }
  @PostMapping("/api/v1/operator/reviews/{id}/complete")
  Map<String, Object> complete(@PathVariable String id, @RequestAttribute("operator") OperatorAccounts.Principal operator,
      @Valid @RequestBody Completion completion) {
    OperatorController.require(operator, OperatorAccounts.Role.REVIEWER);
    return reviews.complete(id, operator.id(), completion.outcome());
  }
}

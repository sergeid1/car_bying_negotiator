package com.dealanalyzer.purchase;

import com.dealanalyzer.operations.OperatorAccounts;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/operator/contribution/decisions")
class ContributionDecisionController {
  private final ContributionDecisionService service;
  ContributionDecisionController(ContributionDecisionService service) { this.service = service; }
  @PostMapping("/thresholds")
  Object freeze(@RequestAttribute("operator") OperatorAccounts.Principal actor,
      @Valid @RequestBody ContributionDecisionService.Freeze request) {
    requireSupport(actor); return service.freeze(request);
  }
  @GetMapping
  Object history(@RequestAttribute("operator") OperatorAccounts.Principal actor,
      @RequestParam LocalDate from, @RequestParam LocalDate to) {
    requireSupport(actor); return service.history(from, to);
  }
  @PostMapping("/review")
  Object review(@RequestAttribute("operator") OperatorAccounts.Principal actor,
      @RequestParam LocalDate from, @RequestParam LocalDate to) {
    requireSupport(actor); return service.review(from, to);
  }
  @PostMapping
  Object decide(@RequestAttribute("operator") OperatorAccounts.Principal actor,
      @RequestParam LocalDate from, @RequestParam LocalDate to,
      @Valid @RequestBody ContributionDecisionService.RecordDecision request) {
    requireSupport(actor); return service.decide(from, to, request);
  }
  private static void requireSupport(OperatorAccounts.Principal actor) {
    if (actor.role() != OperatorAccounts.Role.SUPPORT) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  }
}

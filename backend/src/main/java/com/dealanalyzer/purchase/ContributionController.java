package com.dealanalyzer.purchase;

import com.dealanalyzer.operations.OperatorAccounts;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/operator/contribution")
class ContributionController {
  private final ContributionService service;
  ContributionController(ContributionService service) { this.service = service; }
  @GetMapping("/metrics")
  Object metrics(@RequestAttribute("operator") OperatorAccounts.Principal actor,
      @RequestParam LocalDate from, @RequestParam LocalDate to) {
    requireSupport(actor); return service.snapshot(from, to);
  }
  @PostMapping("/cases/{caseId}/support-time")
  Object support(@RequestAttribute("operator") OperatorAccounts.Principal actor,
      @PathVariable String caseId, @Valid @RequestBody ContributionService.SupportEntry entry) {
    requireSupport(actor); return service.recordSupport(caseId, entry);
  }
  private static void requireSupport(OperatorAccounts.Principal actor) {
    if (actor.role() != OperatorAccounts.Role.SUPPORT) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  }
}

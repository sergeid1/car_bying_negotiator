package com.dealanalyzer.operations;

import java.util.Map;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/v1/operator")
class OperatorController {
  private final OperationsProjection projections;
  OperatorController(OperationsProjection projections) { this.projections = projections; }
  @GetMapping("/cases/{caseId}")
  Map<String, Object> diagnostics(@PathVariable String caseId, @RequestAttribute("operator") OperatorAccounts.Principal operator) {
    require(operator, OperatorAccounts.Role.SUPPORT);
    return projections.diagnostics(caseId);
  }
  @GetMapping("/cases/{caseId}/deletion")
  Map<String, Object> deletion(@PathVariable String caseId, @RequestAttribute("operator") OperatorAccounts.Principal operator) {
    require(operator, OperatorAccounts.Role.SUPPORT);
    return projections.deletion(caseId);
  }
  @GetMapping("/cases/{caseId}/usage")
  java.util.List<Map<String, Object>> usage(@PathVariable String caseId, @RequestAttribute("operator") OperatorAccounts.Principal operator) {
    require(operator, OperatorAccounts.Role.SUPPORT);
    return projections.usage(caseId);
  }
  @GetMapping("/cases/{caseId}/audit")
  java.util.List<Map<String, Object>> audit(@PathVariable String caseId, @RequestAttribute("operator") OperatorAccounts.Principal operator) {
    require(operator, OperatorAccounts.Role.SUPPORT);
    return projections.audit(caseId);
  }
  @GetMapping("/providers")
  java.util.List<Map<String, Object>> providers(@RequestAttribute("operator") OperatorAccounts.Principal operator) {
    require(operator, OperatorAccounts.Role.SUPPORT);
    return projections.providers();
  }
  static void require(OperatorAccounts.Principal operator, OperatorAccounts.Role role) {
    if (operator.role() != role) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  }
}

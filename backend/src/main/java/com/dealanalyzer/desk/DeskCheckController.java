package com.dealanalyzer.desk;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/cases/{caseId}/desk-check")
public class DeskCheckController {
  private final DeskCheckService desk;
  private final com.fasterxml.jackson.databind.ObjectMapper json;
  public DeskCheckController(DeskCheckService desk, com.fasterxml.jackson.databind.ObjectMapper json) { this.desk = desk; this.json = json; }
  @GetMapping
  public com.fasterxml.jackson.databind.JsonNode view(@PathVariable String caseId, @RequestHeader("Case-Access-Token") String token) {
    return com.dealanalyzer.report.FindingText.annotateDesk(json.valueToTree(desk.view(caseId, token)), json);
  }
}

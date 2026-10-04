package com.dealanalyzer.desk;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/cases/{caseId}/desk-check")
public class DeskCheckController {
  private final DeskCheckService desk;
  public DeskCheckController(DeskCheckService desk) { this.desk = desk; }
  @GetMapping
  public DeskCheckService.View view(@PathVariable String caseId, @RequestHeader("Case-Access-Token") String token) {
    return desk.view(caseId, token);
  }
}

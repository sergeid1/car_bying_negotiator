package com.dealanalyzer.pilot;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/cases/{caseId}/feedback/report-total")
class ReportTotalController {
  private final ReportTotalService service;
  ReportTotalController(ReportTotalService service) { this.service = service; }
  @PostMapping
  Object open(@PathVariable String caseId, @RequestHeader("Case-Access-Token") String token,
      @Valid @RequestBody ReportTotalService.Open input) { return service.open(caseId, token, input); }
  @PostMapping("/{id}")
  Object identify(@PathVariable String caseId, @PathVariable String id, @RequestHeader("Case-Access-Token") String token,
      @Valid @RequestBody ReportTotalService.Answer input) { return service.identify(caseId, token, id, input); }
}

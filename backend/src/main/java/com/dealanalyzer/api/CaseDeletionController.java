package com.dealanalyzer.api;

import com.dealanalyzer.buyingcase.persistence.CaseDeletionService;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/v1/cases/{caseId}/deletion")
class CaseDeletionController {
  private final CaseDeletionService deletion;
  CaseDeletionController(CaseDeletionService deletion) { this.deletion = deletion; }
  @PostMapping @ResponseStatus(HttpStatus.ACCEPTED)
  CaseDeletionService.Receipt request(@PathVariable String caseId,
      @RequestHeader(value = "Case-Access-Token", required = false) String token) {
    return deletion.request(caseId, token);
  }
  @GetMapping
  CaseDeletionService.Receipt receipt(@PathVariable String caseId,
      @RequestHeader(value = "Case-Access-Token", required = false) String token) {
    return deletion.receipt(caseId, token);
  }
}

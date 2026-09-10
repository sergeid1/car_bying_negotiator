package com.dealanalyzer.api;

import com.dealanalyzer.buyingcase.persistence.CaseRetentionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/cases/{caseId}/retention")
class CaseRetentionController {
  private final CaseRetentionService retention;
  CaseRetentionController(CaseRetentionService retention) { this.retention = retention; }
  @PostMapping
  CaseRetentionService.Policy shorten(@PathVariable String caseId,
      @RequestHeader(value = "Case-Access-Token", required = false) String token,
      @Valid @RequestBody Choice choice) {
    return retention.shorten(caseId, token, choice.days());
  }
  record Choice(@NotNull @Min(1) @Max(30) Integer days) {}
}

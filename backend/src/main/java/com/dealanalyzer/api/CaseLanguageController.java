package com.dealanalyzer.api;

import com.dealanalyzer.buyingcase.EphemeralBuyingCaseService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/cases/{caseId}/language")
public class CaseLanguageController {
  private final EphemeralBuyingCaseService cases;
  public CaseLanguageController(EphemeralBuyingCaseService cases) { this.cases = cases; }
  public record Change(@NotNull @Pattern(regexp = "en|es") String language) {}
  @PutMapping
  public EphemeralBuyingCaseService.BuyingCaseView change(@PathVariable String caseId,
      @RequestHeader("Case-Access-Token") String token, @Valid @RequestBody Change change) {
    return cases.changeLanguage(caseId, token, change.language());
  }
}

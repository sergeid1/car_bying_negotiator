package com.dealanalyzer.api;

import com.dealanalyzer.buyingcase.persistence.OutsideFinancingService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/cases/{caseId}/outside-finance-offers")
@Validated
public class OutsideFinancingController {
  private final OutsideFinancingService financing;
  public OutsideFinancingController(OutsideFinancingService financing) { this.financing = financing; }
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public OutsideFinancingService.View compare(@PathVariable String caseId,
      @RequestHeader("Case-Access-Token") String token,
      @NotBlank @Size(max = 200) @RequestHeader("Idempotency-Key") String key,
      @RequestBody OutsideFinancingService.Request request) {
    return financing.compare(caseId, token, key, request);
  }
  @GetMapping("/latest")
  public OutsideFinancingService.View latest(@PathVariable String caseId, @RequestHeader("Case-Access-Token") String token) {
    return financing.latest(caseId, token);
  }
  @GetMapping("/{version}")
  public OutsideFinancingService.View version(@PathVariable String caseId, @RequestHeader("Case-Access-Token") String token,
      @PathVariable int version) { return financing.version(caseId, token, version); }
}

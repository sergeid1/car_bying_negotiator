package com.dealanalyzer.plan;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/cases/{caseId}")
@Validated
public class PricePlanController {
  private final PricePlanService plans;
  public PricePlanController(PricePlanService plans) { this.plans = plans; }

  @PostMapping("/price-plans")
  @ResponseStatus(HttpStatus.CREATED)
  public PricePlanService.Snapshot propose(@PathVariable String caseId,
      @RequestHeader("Case-Access-Token") String token,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String key,
      @RequestBody(required = false) PricePlanService.Limits limits) {
    return plans.propose(caseId, token, key, limits);
  }

  @PostMapping("/approved-baselines")
  @ResponseStatus(HttpStatus.CREATED)
  public PricePlanService.Snapshot approve(@PathVariable String caseId,
      @RequestHeader("Case-Access-Token") String token,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String key,
      @RequestBody PricePlanService.Approval approval) {
    return plans.approve(caseId, token, key, approval);
  }

  @GetMapping("/price-plans/{version}")
  public PricePlanService.Snapshot plan(@PathVariable String caseId, @PathVariable String version,
      @RequestHeader("Case-Access-Token") String token) {
    return plans.get(caseId, token, "PLAN", "latest".equals(version) ? null : Integer.valueOf(version));
  }

  @GetMapping("/approved-baselines/{version}")
  public PricePlanService.Snapshot baseline(@PathVariable String caseId, @PathVariable String version,
      @RequestHeader("Case-Access-Token") String token) {
    return plans.get(caseId, token, "BASELINE", "latest".equals(version) ? null : Integer.valueOf(version));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail invalid(IllegalArgumentException error) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, error.getMessage());
  }
}

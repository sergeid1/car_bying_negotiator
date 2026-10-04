package com.dealanalyzer.mandate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/cases/{caseId}/mandates")
@Validated
public class MandateController {
  private final MandateService mandates;
  public MandateController(MandateService mandates) { this.mandates = mandates; }

  @GetMapping("/proposal")
  public MandateService.Proposal proposal(@PathVariable String caseId,
      @RequestHeader("Case-Access-Token") String token) {
    return mandates.proposal(caseId, token);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public MandateService.Mandate approve(@PathVariable String caseId,
      @RequestHeader("Case-Access-Token") String token,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String key,
      @RequestBody MandatePolicy.Request request) {
    return mandates.approve(caseId, token, key, request);
  }

  @GetMapping
  public List<MandateService.Mandate> list(@PathVariable String caseId,
      @RequestHeader("Case-Access-Token") String token) {
    return mandates.list(caseId, token);
  }

  @GetMapping("/{version}")
  public MandateService.Mandate get(@PathVariable String caseId, @PathVariable int version,
      @RequestHeader("Case-Access-Token") String token) {
    return mandates.get(caseId, token, version);
  }

  @PostMapping("/{version}/revocation")
  public MandateService.Mandate revoke(@PathVariable String caseId, @PathVariable int version,
      @RequestHeader("Case-Access-Token") String token,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String key) {
    return mandates.revoke(caseId, token, key, version);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail invalid(IllegalArgumentException error) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, error.getMessage());
  }

  @ExceptionHandler({MandateService.MandateUnavailableException.class, MandateService.MandateConflictException.class})
  ProblemDetail conflict(RuntimeException error) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, error.getMessage());
  }
}

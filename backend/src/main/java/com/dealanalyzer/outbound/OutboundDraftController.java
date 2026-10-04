package com.dealanalyzer.outbound;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/v1/cases/{caseId}/outbound-drafts")
public class OutboundDraftController {
  private final OutboundDraftService drafts;
  public OutboundDraftController(OutboundDraftService drafts) { this.drafts = drafts; }
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public OutboundDraftService.StoredDraft create(@PathVariable String caseId,
      @RequestHeader("Case-Access-Token") String token,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String key,
      @RequestBody OutboundGate.Draft draft) {
    return drafts.create(caseId, token, key, draft);
  }
  @GetMapping
  public List<OutboundDraftService.StoredDraft> list(@PathVariable String caseId,
      @RequestHeader("Case-Access-Token") String token) { return drafts.list(caseId, token); }
  @GetMapping("/{draftId}")
  public OutboundDraftService.StoredDraft get(@PathVariable String caseId, @PathVariable String draftId,
      @RequestHeader("Case-Access-Token") String token) { return drafts.get(caseId, token, draftId); }
  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail invalid(IllegalArgumentException failure) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, failure.getMessage());
  }
}

package com.dealanalyzer.quote;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/v1/cases/{caseId}/negotiation-conversation")
public class NegotiationConversationController {
  private final NegotiationConversationService conversation;
  public NegotiationConversationController(NegotiationConversationService conversation) { this.conversation = conversation; }
  @GetMapping
  public NegotiationConversationService.View view(@PathVariable String caseId, @RequestHeader("Case-Access-Token") String token) {
    return conversation.view(caseId, token);
  }
  @PostMapping("/drafts")
  @ResponseStatus(HttpStatus.CREATED)
  public NegotiationConversationService.Draft create(@PathVariable String caseId, @RequestHeader("Case-Access-Token") String token,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String key, @RequestBody NegotiationConversationService.Create request) {
    return conversation.create(caseId, token, key, request);
  }
  @PostMapping("/drafts/{id}/revisions")
  public NegotiationConversationService.Draft edit(@PathVariable String caseId, @PathVariable String id,
      @RequestHeader("Case-Access-Token") String token, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String key,
      @RequestBody NegotiationConversationService.Edit edit) {
    return conversation.edit(caseId, token, id, key, edit);
  }
  @PostMapping("/drafts/{id}/approval")
  public NegotiationConversationService.Draft approve(@PathVariable String caseId, @PathVariable String id,
      @RequestHeader("Case-Access-Token") String token, @RequestBody NegotiationConversationService.Approval approval) {
    return conversation.approveAndSend(caseId, token, id, approval);
  }
  @PostMapping("/drafts/{id}/decline")
  public NegotiationConversationService.Draft decline(@PathVariable String caseId, @PathVariable String id,
      @RequestHeader("Case-Access-Token") String token) {
    return conversation.decline(caseId, token, id);
  }
  @PostMapping("/stop")
  public NegotiationConversationService.View stop(@PathVariable String caseId, @RequestHeader("Case-Access-Token") String token) {
    return conversation.stop(caseId, token);
  }
  @PostMapping("/resume")
  public NegotiationConversationService.View resume(@PathVariable String caseId, @RequestHeader("Case-Access-Token") String token) {
    return conversation.resume(caseId, token);
  }
  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail invalid(IllegalArgumentException failure) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, failure.getMessage());
  }
}

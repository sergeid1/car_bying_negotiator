package com.dealanalyzer.api;

import com.dealanalyzer.buyingcase.persistence.NegotiationService;
import com.dealanalyzer.negotiation.ReplyAssistant;
import com.dealanalyzer.negotiation.ReplyDraft;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;

/** Saved drafts and reply suggestions. There is no endpoint that sends anything to a Dealer. */
@RestController
@RequestMapping("/api/v1/cases/{caseId}/reports/{version}/negotiation")
public class NegotiationController {
  private final NegotiationService service;
  public NegotiationController(NegotiationService service) { this.service = service; }
  @GetMapping
  public JsonNode get(@PathVariable String caseId, @PathVariable int version,
      @RequestHeader("Case-Access-Token") String token) {
    return service.get(caseId, version, token);
  }

  /** The Dealer's answer as the Buyer pasted it; untrusted text that is never stored. */
  public record ReplyRequest(String dealerResponse) {}

  @PostMapping("/replies")
  public ReplyDraft reply(@PathVariable String caseId, @PathVariable int version,
      @RequestHeader("Case-Access-Token") String token, @RequestBody ReplyRequest request) {
    return service.reply(caseId, version, token, request == null ? null : request.dealerResponse());
  }

  @ExceptionHandler(ReplyAssistant.InvalidDealerResponse.class)
  ProblemDetail invalidResponse(ReplyAssistant.InvalidDealerResponse error) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, error.getMessage());
    problem.setProperty("failureCategory", "BUYER_INPUT");
    return problem;
  }
}

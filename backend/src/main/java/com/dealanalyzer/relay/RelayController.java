package com.dealanalyzer.relay;

import org.springframework.web.bind.annotation.*;

/**
 * The Buyer's view of the case's private reply-intake address and its dealer messages. Nothing here sends; the
 * Buyer sends approved messages from their own mailbox (ticket 84).
 */
@RestController
@RequestMapping("/api/v1/cases/{caseId}/relay")
public class RelayController {
  private final RelayService relay;
  public RelayController(RelayService relay) { this.relay = relay; }

  @GetMapping
  public RelayService.RelayView view(@PathVariable String caseId, @RequestHeader("Case-Access-Token") String token) {
    return relay.view(caseId, token);
  }

  @GetMapping("/messages/{messageId}")
  public RelayService.MessageContent message(@PathVariable String caseId, @PathVariable String messageId,
      @RequestHeader("Case-Access-Token") String token) {
    return relay.message(caseId, token, messageId);
  }
}

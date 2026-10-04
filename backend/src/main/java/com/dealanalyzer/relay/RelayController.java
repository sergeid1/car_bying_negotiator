package com.dealanalyzer.relay;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;

/** The Buyer's view of the case's dealer email relay. Sending is done by later tickets through the gate. */
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

  @ExceptionHandler(RelayService.RelayRefusedException.class)
  ProblemDetail refused(RelayService.RelayRefusedException error) {
    var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, error.getMessage());
    problem.setProperty("reason", error.reason());
    return problem;
  }
}

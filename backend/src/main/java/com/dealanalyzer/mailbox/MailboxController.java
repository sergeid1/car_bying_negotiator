package com.dealanalyzer.mailbox;

import com.dealanalyzer.account.AccountSession;
import com.dealanalyzer.relay.DealerMessageHandoff;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * The Buyer's Mailbox Connection (ticket 83): connect Gmail or Outlook with a send-only permission, see and
 * disconnect it, and send an approved dealer message through it. Account endpoints use the account session
 * cookie; sending also needs the case credential.
 */
@RestController
@Validated
@RequestMapping("/api/v1")
public class MailboxController {
  private final MailboxConnections connections;
  private final MailboxSendService sends;
  private final com.dealanalyzer.account.BuyerAccountService accounts;

  MailboxController(MailboxConnections connections, MailboxSendService sends, com.dealanalyzer.account.BuyerAccountService accounts) {
    this.connections = connections; this.sends = sends; this.accounts = accounts;
  }

  public record StartRequest(@NotNull MailboxProvider provider) {}
  public record CompleteRequest(@NotBlank @Size(max = 200) String state, @NotBlank @Size(max = 4096) String code) {
    @Override public String toString() { return "CompleteRequest[redacted]"; }
  }

  @GetMapping("/account/mailbox")
  public MailboxConnections.MailboxView view(@AccountSession String session) {
    return connections.view(session);
  }

  @PostMapping("/account/mailbox/authorizations")
  public MailboxConnections.Authorization start(@AccountSession String session, @Valid @RequestBody StartRequest request) {
    accounts.requireMailboxStepUp(session);
    return connections.start(session, request.provider());
  }

  @PostMapping("/account/mailbox/connection")
  public MailboxConnections.MailboxView complete(@AccountSession String session, @Valid @RequestBody CompleteRequest request) {
    accounts.requireMailboxStepUp(session);
    return connections.complete(session, request.state(), request.code());
  }

  @DeleteMapping("/account/mailbox/connection")
  public MailboxConnections.MailboxView disconnect(@AccountSession String session) {
    accounts.requireMailboxStepUp(session);
    return connections.disconnect(session);
  }

  @PostMapping("/cases/{caseId}/handoffs/{messageId}/mailbox-send")
  public DealerMessageHandoff.Handoff send(@PathVariable String caseId, @PathVariable String messageId,
      @RequestHeader("Case-Access-Token") String token, @AccountSession String session) {
    return sends.send(caseId, token, session, messageId);
  }

  @ExceptionHandler(MailboxProblem.class)
  ResponseEntity<ProblemDetail> problem(MailboxProblem problem) {
    var body = ProblemDetail.forStatusAndDetail(problem.status, problem.getMessage());
    body.setProperty("code", problem.code);
    if (problem.ownEmailApp) body.setProperty("ownEmailApp", true);
    return ResponseEntity.status(problem.status).body(body);
  }

  @ExceptionHandler(DealerMessageHandoff.HandoffRefusedException.class)
  ProblemDetail refused(DealerMessageHandoff.HandoffRefusedException error) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, error.getMessage());
  }
}

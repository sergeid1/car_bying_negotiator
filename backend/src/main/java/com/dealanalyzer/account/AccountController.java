package com.dealanalyzer.account;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** Buyer Account API. Every account endpoint is authorized by the Account-Session-Token header. */
@RestController @RequestMapping("/api/v1/account") @Validated
public class AccountController {
  private static final String SESSION = "Account-Session-Token";
  private final BuyerAccountService accounts;
  private final AccountExportService exports;
  private final AccountDeletionService deletion;

  AccountController(BuyerAccountService accounts, AccountExportService exports, AccountDeletionService deletion) {
    this.accounts = accounts; this.exports = exports; this.deletion = deletion;
  }

  public record SignInRequest(@NotBlank @Email @Size(max = 254) String email) {}
  public record VerifyRequest(@NotBlank @Size(max = 128) String token) {}
  public record ContactRequest(@Size(max = 100) String firstName, @Size(max = 100) String lastName,
      @Size(max = 32) @Pattern(regexp = "^$|^[+]?[0-9 ().-]{7,32}$") String phone) {}
  public record AttachRequest(@NotBlank @Size(max = 36) String caseId) {}

  @PostMapping("/sign-in-requests") @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String, String> requestSignIn(@RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String key,
      @Valid @RequestBody SignInRequest request) {
    accounts.requestSignIn(request.email(), key);
    // The same acknowledgement for new, existing and pending-deletion accounts.
    return Map.of("status", "Check your email for a single-use sign-in link. It expires in 15 minutes.");
  }

  @PostMapping("/sign-in/verify")
  public BuyerAccountService.Session verify(@Valid @RequestBody VerifyRequest request) {
    return accounts.verify(request.token());
  }

  @GetMapping
  public BuyerAccountService.AccountView view(@RequestHeader(value = SESSION, required = false) String session) {
    return accounts.view(session);
  }

  @PutMapping("/contact")
  public BuyerAccountService.AccountView updateContact(@RequestHeader(value = SESSION, required = false) String session,
      @Valid @RequestBody ContactRequest request) {
    return accounts.updateContact(session, request.firstName(), request.lastName(), request.phone());
  }

  @PostMapping("/cases")
  public BuyerAccountService.AccountView attach(@RequestHeader(value = SESSION, required = false) String session,
      @RequestHeader("Case-Access-Token") String caseToken, @Valid @RequestBody AttachRequest request) {
    return accounts.attach(session, request.caseId(), caseToken);
  }

  @PostMapping("/sign-out") @ResponseStatus(HttpStatus.NO_CONTENT)
  public void signOut(@RequestHeader(value = SESSION, required = false) String session,
      @RequestParam(defaultValue = "false") boolean everywhere) {
    accounts.signOut(session, everywhere);
  }

  @PostMapping("/export")
  public ResponseEntity<byte[]> export(@RequestHeader(value = SESSION, required = false) String session) {
    return ResponseEntity.ok().header("Content-Type", "application/zip")
        .header("Content-Disposition", "attachment; filename=buyer-account-export.zip").body(exports.export(session));
  }

  @PostMapping("/deletion") @ResponseStatus(HttpStatus.ACCEPTED)
  public AccountDeletionService.Receipt delete(@RequestHeader(value = SESSION, required = false) String session) {
    return deletion.request(session);
  }

  @GetMapping("/deletion")
  public AccountDeletionService.Receipt deletionReceipt(@RequestHeader(value = SESSION, required = false) String session) {
    return deletion.receipt(session);
  }

  /** Reviewed account outcomes keep their code and fixed detail; nothing echoes Buyer input. */
  @RestControllerAdvice(assignableTypes = {AccountController.class, com.dealanalyzer.notification.NotificationController.class})
  @Order(Ordered.HIGHEST_PRECEDENCE)
  static class Problems {
    @ExceptionHandler(AccountProblem.class)
    ResponseEntity<ProblemDetail> problem(AccountProblem problem) {
      var body = ProblemDetail.forStatusAndDetail(problem.status, problem.getMessage());
      body.setProperty("code", problem.code);
      return ResponseEntity.status(problem.status).body(body);
    }
  }
}

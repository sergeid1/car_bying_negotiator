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

/** Buyer Account API. Account endpoints use the HttpOnly account session cookie. */
@RestController @RequestMapping("/api/v1/account") @Validated
public class AccountController {
  private final BuyerAccountService accounts;
  private final AccountSessionCookie cookies;
  private final AccountExportService exports;
  private final AccountDeletionService deletion;

  AccountController(BuyerAccountService accounts, AccountExportService exports, AccountDeletionService deletion, AccountSessionCookie cookies) {
    this.accounts = accounts; this.exports = exports; this.deletion = deletion; this.cookies = cookies;
  }

  public record SignInRequest(@NotBlank @Email @Size(max = 254) String email,
      @Pattern(regexp = "en|es") String language) {
    public SignInRequest(String email) { this(email, "en"); }
  }
  public record VerifyRequest(@NotBlank @Size(max = 128) String token) {}
  public record ContactRequest(@Size(max = 100) String firstName, @Size(max = 100) String lastName,
      @Size(max = 32) @Pattern(regexp = "^$|^[+]?[0-9 ().-]{7,32}$") String phone) {}
  public record LanguageRequest(@NotBlank @Size(max = 8) String language) {}
  public record AttachRequest(@NotBlank @Size(max = 36) String caseId) {}

  @PostMapping("/sign-in-requests") @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String, String> requestSignIn(@RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String key,
      @Valid @RequestBody SignInRequest request) {
    accounts.requestSignIn(request.email(), key, request.language());
    // The same acknowledgement for new, existing and pending-deletion accounts.
    return Map.of("status", "Check your email for a single-use sign-in link. It expires in 15 minutes.");
  }

  public record SignedIn(java.time.Instant expiresAt) {}

  @PostMapping("/sign-in/verify")
  public SignedIn verify(@Valid @RequestBody VerifyRequest request, jakarta.servlet.http.HttpServletResponse response) {
    var session = accounts.verify(request.token());
    cookies.issue(response, session);
    return new SignedIn(session.expiresAt());
  }

  @GetMapping
  public BuyerAccountService.AccountView view(@com.dealanalyzer.account.AccountSession String session) {
    return accounts.view(session);
  }

  @PutMapping("/contact")
  public BuyerAccountService.AccountView updateContact(@com.dealanalyzer.account.AccountSession String session,
      @Valid @RequestBody ContactRequest request) {
    return accounts.updateContact(session, request.firstName(), request.lastName(), request.phone());
  }

  @PutMapping("/language")
  public BuyerAccountService.AccountView updateLanguage(@com.dealanalyzer.account.AccountSession String session,
      @Valid @RequestBody LanguageRequest request) {
    return accounts.updateLanguage(session, request.language());
  }

  @PostMapping("/cases")
  public BuyerAccountService.AccountView attach(@com.dealanalyzer.account.AccountSession String session,
      @RequestHeader("Case-Access-Token") String caseToken, @Valid @RequestBody AttachRequest request) {
    return accounts.attach(session, request.caseId(), caseToken);
  }

  /** Opens an attached case for this session; the pass returned is not a case link and ends with the session. */
  @PostMapping("/cases/{caseId}/open")
  public BuyerAccountService.OpenedCase open(@com.dealanalyzer.account.AccountSession String session,
      @PathVariable @Size(max = 36) String caseId) {
    return accounts.open(session, caseId);
  }

  @PostMapping("/sign-out") @ResponseStatus(HttpStatus.NO_CONTENT)
  public void signOut(@com.dealanalyzer.account.AccountSession String session,
      @RequestParam(defaultValue = "false") boolean everywhere, jakarta.servlet.http.HttpServletResponse response) {
    accounts.signOut(session, everywhere);
    cookies.clear(response);
  }

  @PostMapping("/export")
  public ResponseEntity<byte[]> export(@com.dealanalyzer.account.AccountSession String session) {
    return ResponseEntity.ok().header("Content-Type", "application/zip")
        .header("Content-Disposition", "attachment; filename=buyer-account-export.zip").body(exports.export(session));
  }

  @PostMapping("/deletion") @ResponseStatus(HttpStatus.ACCEPTED)
  public AccountDeletionService.Receipt delete(@com.dealanalyzer.account.AccountSession String session) {
    return deletion.request(session);
  }

  @GetMapping("/deletion")
  public AccountDeletionService.Receipt deletionReceipt(@com.dealanalyzer.account.AccountSession String session,
      jakarta.servlet.http.HttpServletResponse response) {
    AccountDeletionService.Receipt receipt;
    try { receipt = deletion.receipt(session); }
    catch (AccountProblem unknown) {
      cookies.clear(response);
      throw unknown;
    }
    if ("DELETED".equals(receipt.state())) cookies.clear(response);
    return receipt;
  }

  /** Reviewed account outcomes keep their code and fixed detail; nothing echoes Buyer input. */
  @RestControllerAdvice(assignableTypes = {AccountController.class, PasskeyController.class, PasswordController.class, SocialSignInController.class, com.dealanalyzer.notification.NotificationController.class,
      com.dealanalyzer.journey.JourneyController.class, com.dealanalyzer.appointment.TestDriveController.class,
      com.dealanalyzer.mailbox.MailboxController.class, com.dealanalyzer.insurance.InsuranceController.class,
      com.dealanalyzer.loan.LoanController.class})
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

package com.dealanalyzer.account;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/account/passkeys") @Validated
public class PasskeyController {
  private final PasskeyService passkeys;
  private final AccountSessionCookie cookies;
  PasskeyController(PasskeyService passkeys, AccountSessionCookie cookies) { this.passkeys = passkeys; this.cookies = cookies; }
  public record Registration(@NotBlank @Size(max = 36) String attemptId, @NotBlank @Size(max = 80) String nickname,
      @NotBlank @Size(max = 20000) String credential) {}
  public record Authentication(@NotBlank @Size(max = 36) String attemptId, @NotBlank @Size(max = 20000) String credential) {}
  @GetMapping public List<PasskeyService.Metadata> list(@AccountSession String session) { return passkeys.list(session); }
  @PostMapping("/registration/options") public PasskeyService.Options registerOptions(@AccountSession String session) {
    return passkeys.startRegistration(session);
  }
  @PostMapping("/registration") public List<PasskeyService.Metadata> register(@AccountSession String session,
      @Valid @RequestBody Registration request) {
    return passkeys.finishRegistration(session, request.attemptId(), request.nickname(), request.credential());
  }
  @DeleteMapping("/{id}") public List<PasskeyService.Metadata> remove(@AccountSession String session,
      @PathVariable @Size(max = 36) String id) { return passkeys.remove(session, id); }
  @PostMapping("/sign-in/options") public PasskeyService.Options signInOptions() { return passkeys.startSignIn(); }
  @PostMapping("/sign-in") public AccountController.SignedIn signIn(@Valid @RequestBody Authentication request,
      jakarta.servlet.http.HttpServletResponse response) {
    var session = passkeys.finishSignIn(request.attemptId(), request.credential());
    cookies.issue(response, session); return new AccountController.SignedIn(session.expiresAt());
  }
}

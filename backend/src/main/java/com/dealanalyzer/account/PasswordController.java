package com.dealanalyzer.account;

import com.dealanalyzer.api.ClientAddress;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/account") @Validated
public class PasswordController {
  private final PasswordService passwords;
  private final AccountSessionCookie cookies;
  private final ClientAddress clients;
  PasswordController(PasswordService passwords, AccountSessionCookie cookies, ClientAddress clients) {
    this.passwords = passwords; this.cookies = cookies; this.clients = clients;
  }
  public record Password(@NotNull @Size(max = 144) String password) {
    @Override public String toString() { return "Password[redacted]"; }
  }
  public record SignIn(@NotBlank @Email @Size(max = 254) String email, @NotNull @Size(max = 144) String password) {
    @Override public String toString() { return "SignIn[redacted]"; }
  }
  @GetMapping("/password") public PasswordService.Status status(@AccountSession String session) { return passwords.status(session); }
  @PutMapping("/password") public PasswordService.Status set(@AccountSession String session, @Valid @RequestBody Password request) {
    return passwords.set(session, request.password());
  }
  @DeleteMapping("/password") public PasswordService.Status remove(@AccountSession String session) { return passwords.remove(session); }
  @PostMapping("/password/sign-in") public AccountController.SignedIn signIn(@Valid @RequestBody SignIn request,
      jakarta.servlet.http.HttpServletRequest http, jakarta.servlet.http.HttpServletResponse response) {
    var session = passwords.signIn(request.email(), request.password(), clients.of(http));
    cookies.issue(response, session); return new AccountController.SignedIn(session.expiresAt());
  }
}

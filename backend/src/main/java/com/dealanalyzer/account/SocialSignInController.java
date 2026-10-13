package com.dealanalyzer.account;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * Sign in with Google or Apple (ticket 113). Start and complete are called by the Buyer's page with the
 * {@code Account-Request} header; the provider returns the browser to the callbacks, which only record the code and
 * send the browser back to the page. The binding cookie ties an attempt to the browser that started it.
 */
@RestController @RequestMapping(SocialSignInController.ROOT) @Validated
public class SocialSignInController {
  static final String ROOT = "/api/v1/account/social-sign-in";
  static final String BINDING_COOKIE = "social_sign_in_binding";

  private final SocialSignInService social;
  private final AccountSessionCookie cookies;
  private final boolean secure;

  SocialSignInController(SocialSignInService social, AccountSessionCookie cookies,
      @org.springframework.beans.factory.annotation.Value("${account.session.cookie-secure:true}") boolean secure) {
    this.social = social; this.cookies = cookies; this.secure = secure;
  }

  public record Providers(List<SocialProvider> providers) {}
  public record StartRequest(@NotNull SocialProvider provider) {}
  public record Authorization(String authorizationUrl) {
    @Override public String toString() { return "Authorization[redacted]"; }
  }
  public record CompleteRequest(String state) {
    @Override public String toString() { return "CompleteRequest[redacted]"; }
  }
  /** The social complete response: the session expiry and the provider used. */
  public record SocialSignedIn(Instant expiresAt, SocialProvider provider) {}

  @GetMapping("/providers")
  public Providers providers() { return new Providers(social.providers()); }

  @PostMapping
  public Authorization start(@RequestHeader(value = "Account-Request", required = false) String marker,
      @CookieValue(value = BINDING_COOKIE, required = false) String binding,
      @Valid @RequestBody StartRequest request, HttpServletResponse response) {
    requireAccountRequest(marker);
    var started = social.start(request.provider(), binding);
    binding(response, started.binding(), social.attemptLifetime().toSeconds());
    return new Authorization(started.authorizationUrl());
  }

  @GetMapping("/google/callback")
  public ResponseEntity<Void> googleCallback(@RequestParam(required = false) String state,
      @RequestParam(required = false) String code, @RequestParam(required = false) String error) {
    return seeOther(social.callback(SocialProvider.GOOGLE, state, code, error));
  }

  /** Apple returns with a cross-site form post because the {@code email} scope requires {@code form_post}. */
  @PostMapping(path = "/apple/callback", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
  public ResponseEntity<Void> appleCallback(@RequestParam(required = false) String state,
      @RequestParam(required = false) String code, @RequestParam(required = false) String error) {
    return seeOther(social.callback(SocialProvider.APPLE, state, code, error));
  }

  @PostMapping("/complete")
  public SocialSignedIn complete(@RequestHeader(value = "Account-Request", required = false) String marker,
      @CookieValue(value = BINDING_COOKIE, required = false) String binding,
      @RequestBody CompleteRequest request, HttpServletResponse response) {
    requireAccountRequest(marker);
    var completed = social.complete(request == null ? null : request.state(), binding);
    cookies.issue(response, completed.session());
    binding(response, "", 0);
    return new SocialSignedIn(completed.session().expiresAt(), completed.provider());
  }

  private static ResponseEntity<Void> seeOther(String location) {
    return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(location)).build();
  }

  private void binding(HttpServletResponse response, String value, long seconds) {
    response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(BINDING_COOKIE, value).httpOnly(true).secure(secure)
        .sameSite("Strict").path(ROOT).maxAge(seconds).build().toString());
  }

  private static void requireAccountRequest(String marker) {
    if (!"1".equals(marker)) throw new AccountProblem(HttpStatus.FORBIDDEN, "ACCOUNT_REQUEST_REQUIRED",
        "This account request needs the Account-Request header. Reload the page and retry.");
  }
}

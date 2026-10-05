package com.dealanalyzer.account;

import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class AccountSessionCookie {
  public static final String NAME = "account_session";
  private final boolean secure;

  AccountSessionCookie(@Value("${account.session.cookie-secure:true}") boolean secure, Environment environment) {
    if (!secure && Stream.concat(Stream.of(environment.getActiveProfiles()),
        Stream.of(environment.getProperty("evidence.deployment", "development"))).map(p -> p.toLowerCase(Locale.ROOT))
        .anyMatch(p -> p.equals("pilot") || p.equals("production") || p.equals("prod")))
      throw new IllegalStateException("Account session cookies must be secure for pilot/production");
    this.secure = secure;
  }

  void issue(HttpServletResponse response, BuyerAccountService.Session session) {
    // Round up to avoid ending the browser session before the server's expiry.
    long seconds = Math.max(0, (Duration.between(Instant.now(), session.expiresAt()).toMillis() + 999) / 1000);
    write(response, session.sessionToken(), seconds);
  }

  public void clear(HttpServletResponse response) { write(response, "", 0); }

  private void write(HttpServletResponse response, String token, long seconds) {
    response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(NAME, token).httpOnly(true).secure(secure)
        .sameSite("Strict").path("/api/v1").maxAge(seconds).build().toString());
  }
}

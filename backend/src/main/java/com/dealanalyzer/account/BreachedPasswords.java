package com.dealanalyzer.account;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Pwned Passwords k-anonymity: no password, full digest or email leaves this boundary. */
@Component
public class BreachedPasswords {
  private final boolean enabled;
  private final String url;
  private final Duration timeout;
  private final HttpClient client;

  BreachedPasswords(@Value("${account.password.breach-check.enabled:true}") boolean enabled,
      @Value("${account.password.breach-check.url:https://api.pwnedpasswords.com/range/}") String url,
      @Value("${account.password.breach-check.timeout:PT2S}") Duration timeout) {
    if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(5)) > 0)
      throw new IllegalArgumentException("Password breach timeout must be positive and at most five seconds");
    this.enabled = enabled; this.url = url; this.timeout = timeout;
    client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
  }

  public boolean contains(String password) {
    if (!enabled) return false;
    try {
      // The range API is keyed by SHA-1; this is a lookup key, not credential protection (bcrypt stores passwords).
      String hash = HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-1") // nosemgrep: scripts.no-weak-digest
          .digest(password.getBytes(StandardCharsets.UTF_8)));
      var request = HttpRequest.newBuilder(URI.create(url + hash.substring(0, 5))).timeout(timeout)
          .header("Add-Padding", "true").header("User-Agent", "Deal-X-Ray-password-check").GET().build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.US_ASCII));
      if (response.statusCode() != 200) throw new IllegalStateException("Breach check unavailable");
      boolean matched = false;
      for (String line : response.body().split("\\r?\\n")) {
        String[] parts = line.split(":", -1);
        if (parts.length != 2 || !parts[0].matches("[0-9A-Fa-f]{35}") || !parts[1].matches("[0-9]+"))
          throw new IllegalStateException("Breach response invalid");
        if (parts[0].equalsIgnoreCase(hash.substring(5)) && Long.parseLong(parts[1]) > 0) matched = true;
      }
      return matched;
    } catch (Exception unavailable) {
      if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
      // Exception text may contain a URI/digest prefix; never log it.
      LoggerFactory.getLogger(BreachedPasswords.class).warn("PASSWORD_BREACH_CHECK_UNAVAILABLE: password check skipped");
      return false;
    }
  }
}

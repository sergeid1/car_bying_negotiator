package com.dealanalyzer.buyingcase;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Function;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class CaseAccessService {
  private static final int TOKEN_BYTES = 32;
  private static final int MINIMUM_PEPPER_BYTES = 32;

  private final byte[] accessPepper;
  private final Duration accessLifetime;
  private final SecureRandom secureRandom = new SecureRandom();

  CaseAccessService(
      @Value("${case.access.pepper}") String accessPepper,
      @Value("${case.access.lifetime}") Duration accessLifetime) {
    this.accessPepper = accessPepper.getBytes(StandardCharsets.UTF_8);
    if (this.accessPepper.length < MINIMUM_PEPPER_BYTES) {
      throw new IllegalArgumentException("case.access.pepper must contain at least 32 bytes");
    }
    this.accessLifetime = accessLifetime;
  }

  public CaseCredential issue(Instant now) {
    byte[] entropy = new byte[TOKEN_BYTES];
    secureRandom.nextBytes(entropy);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
    return new CaseCredential(token, protect(token), now.plus(accessLifetime));
  }

  public <T> T requireAuthorized(
      Optional<T> candidate,
      String suppliedToken,
      Function<T, String> protectedHash,
      Function<T, Instant> expiresAt) {
    T stored = candidate.orElseThrow(this::notFound);
    if (!verifies(protectedHash.apply(stored), expiresAt.apply(stored), suppliedToken)) {
      throw notFound();
    }
    return stored;
  }

  private boolean verifies(String protectedHash, Instant expiresAt, String suppliedToken) {
    return protectedHash != null
        && expiresAt != null
        && expiresAt.isAfter(Instant.now())
        && suppliedToken != null
        && MessageDigest.isEqual(
            protectedHash.getBytes(StandardCharsets.US_ASCII),
            protect(suppliedToken).getBytes(StandardCharsets.US_ASCII));
  }

  public String protectRecoveryToken(String token) {
    return protect("recovery:" + token);
  }

  private String protect(String token) {
    try {
      Mac hmac = Mac.getInstance("HmacSHA256");
      hmac.init(new SecretKeySpec(accessPepper, "HmacSHA256"));
      return HexFormat.of().formatHex(
          hmac.doFinal(token.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Secure case access is unavailable", exception);
    }
  }

  private ResourceNotFoundException notFound() {
    return new ResourceNotFoundException("Buying Case not found");
  }

  public record CaseCredential(String token, String protectedHash, Instant expiresAt) {}
}

package com.dealanalyzer.buyingcase;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class CaseAccessService {
  private final byte[] accessPepper;
  private final Duration accessLifetime;

  CaseAccessService(
      @Value("${case.access.pepper}") String accessPepper,
      @Value("${case.access.lifetime}") Duration accessLifetime) {
    this.accessPepper = accessPepper.getBytes(StandardCharsets.UTF_8);
    this.accessLifetime = accessLifetime;
  }

  public CaseCredential issue(String caseId, String idempotencyKey, Instant now) {
    String token = token(caseId, idempotencyKey);
    return new CaseCredential(token, sha256(token), now.plus(accessLifetime));
  }

  public String reproduceToken(String caseId, String idempotencyKey) {
    return token(caseId, idempotencyKey);
  }

  public boolean verifies(String protectedHash, Instant expiresAt, String suppliedToken) {
    return protectedHash != null
        && expiresAt != null
        && expiresAt.isAfter(Instant.now())
        && suppliedToken != null
        && MessageDigest.isEqual(
            protectedHash.getBytes(StandardCharsets.US_ASCII),
            sha256(suppliedToken).getBytes(StandardCharsets.US_ASCII));
  }

  private String token(String caseId, String idempotencyKey) {
    try {
      Mac hmac = Mac.getInstance("HmacSHA256");
      hmac.init(new SecretKeySpec(accessPepper, "HmacSHA256"));
      byte[] token = hmac.doFinal(
          ("case-access:" + caseId + ":" + idempotencyKey).getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Secure case access is unavailable", exception);
    }
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Secure case access is unavailable", exception);
    }
  }

  public record CaseCredential(String token, String protectedHash, Instant expiresAt) {}
}

package com.dealanalyzer.buyingcase;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Function;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class CaseAccessService {
  private static final int TOKEN_BYTES = 32;
  private static final int MINIMUM_PEPPER_BYTES = 32;
  private static final String ACCOUNT_PASS_PREFIX = "acct.";

  private final byte[] accessPepper;
  private final Duration accessLifetime;
  private final SecureRandom secureRandom = new SecureRandom();
  private final ObjectProvider<AccountPassAdmission> accountPasses;

  CaseAccessService(String accessPepper, Duration accessLifetime) {
    this(accessPepper, accessLifetime, null);
  }

  @Autowired
  CaseAccessService(
      @Value("${case.access.pepper}") String accessPepper,
      @Value("${case.access.lifetime}") Duration accessLifetime,
      ObjectProvider<AccountPassAdmission> accountPasses) {
    this.accessPepper = accessPepper.getBytes(StandardCharsets.UTF_8);
    if (this.accessPepper.length < MINIMUM_PEPPER_BYTES) {
      throw new IllegalArgumentException("case.access.pepper must contain at least 32 bytes");
    }
    this.accessLifetime = accessLifetime;
    this.accountPasses = accountPasses;
  }

  /**
   * The Buyer Account side of an account case pass. It returns the protected session hash of the
   * current request's account session when that session is active and the case is attached to its
   * account, and null otherwise.
   */
  public interface AccountPassAdmission {
    String admittedSessionHash(String caseId);
  }

  /**
   * Issues a pass that opens one attached case for one account session. The pass is not a case
   * link: it is useless without the same session cookie, ends with that session, and stops working
   * when the case is detached, revoked, expired or its case link changes.
   */
  public CaseCredential issueAccountPass(String caseId, String caseAccessHash, String sessionHash, Instant expiresAt) {
    long epoch = expiresAt.getEpochSecond();
    String token = ACCOUNT_PASS_PREFIX + caseId + "." + epoch + "." + passSignature(caseId, epoch, sessionHash, caseAccessHash);
    return new CaseCredential(token, null, Instant.ofEpochSecond(epoch));
  }

  public static boolean isAccountPass(String token) {
    return token != null && token.startsWith(ACCOUNT_PASS_PREFIX);
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
    if (isAccountPass(suppliedToken)) {
      return protectedHash != null && expiresAt != null && expiresAt.isAfter(Instant.now())
          && verifiesAccountPass(protectedHash, suppliedToken);
    }
    return protectedHash != null
        && expiresAt != null
        && expiresAt.isAfter(Instant.now())
        && suppliedToken != null
        && MessageDigest.isEqual(
            protectedHash.getBytes(StandardCharsets.US_ASCII),
            protect(suppliedToken).getBytes(StandardCharsets.US_ASCII));
  }

  private boolean verifiesAccountPass(String protectedHash, String pass) {
    String[] parts = pass.split("\\.", -1);
    if (parts.length != 4 || parts[1].isEmpty() || parts[1].length() > 36 || accountPasses == null) return false;
    long epoch;
    try {
      epoch = Long.parseLong(parts[2]);
      if (!Instant.ofEpochSecond(epoch).isAfter(Instant.now())) return false;
    } catch (NumberFormatException | DateTimeException malformed) { return false; }
    var admission = accountPasses.getIfAvailable();
    String sessionHash = admission == null ? null : admission.admittedSessionHash(parts[1]);
    return sessionHash != null && MessageDigest.isEqual(
        passSignature(parts[1], epoch, sessionHash, protectedHash).getBytes(StandardCharsets.US_ASCII),
        parts[3].getBytes(StandardCharsets.US_ASCII));
  }

  private String passSignature(String caseId, long epoch, String sessionHash, String caseAccessHash) {
    return protect("account-case-pass:" + caseId + ":" + epoch + ":" + sessionHash + ":" + caseAccessHash);
  }

  public String protectRecoveryToken(String token) {
    return protect("recovery:" + token);
  }

  /** Keyed hash for Buyer Account secrets and lookups, separated by scope from case credentials. */
  public String protectAccountSecret(String scope, String value) {
    return protect("account:" + scope + ":" + value);
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

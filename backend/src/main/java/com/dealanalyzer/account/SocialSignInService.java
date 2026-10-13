package com.dealanalyzer.account;

import com.dealanalyzer.buyingcase.CaseAccessService;
import com.dealanalyzer.notification.BuyerNotifications;
import com.dealanalyzer.notification.NotificationType;
import com.dealanalyzer.time.PersistedInstant;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sign in with Google or Apple (ticket 113): OpenID Connect authorization code flow with PKCE, a nonce and a
 * single-use state bound to the starting browser by an HttpOnly cookie. Only the server redeems the code. An
 * identity is linked to an account by its keyed subject hash, or on first use to the account of its verified email.
 * Provider tokens are discarded once the ID token is checked.
 */
@Service
public class SocialSignInService {
  static final String VERIFIER = "pkce-verifier";
  static final String NONCE = "nonce";
  static final String CODE = "code";
  private static final Set<String> CANCELLED = Set.of("access_denied", "user_cancelled_authorize");

  private final SocialSignInConfiguration configuration;
  private final BuyerAccountService accounts;
  private final BuyerAccountRepository owners;
  private final AccountEnrollment enrollment;
  private final CaseAccessService secrets;
  private final BuyerNotifications notifications;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final String origin;
  private final String encryptionKey;
  private final Duration attemptLifetime;
  private final SecureRandom random = new SecureRandom();

  SocialSignInService(SocialSignInConfiguration configuration, BuyerAccountService accounts, BuyerAccountRepository owners,
      AccountEnrollment enrollment, CaseAccessService secrets, BuyerNotifications notifications, JdbcTemplate jdbc,
      PlatformTransactionManager transactions,
      @Value("${case.recovery.public-url:http://localhost:5173/}") String origin,
      @Value("${document.storage.encryption-key:}") String encryptionKey,
      @Value("${account.social-sign-in.attempt-lifetime:PT10M}") Duration attemptLifetime) {
    if (attemptLifetime.isNegative() || attemptLifetime.isZero() || attemptLifetime.compareTo(Duration.ofHours(1)) > 0)
      throw new IllegalArgumentException("account.social-sign-in.attempt-lifetime must be positive and at most one hour");
    this.configuration = configuration; this.accounts = accounts; this.owners = owners; this.enrollment = enrollment;
    this.secrets = secrets; this.notifications = notifications; this.jdbc = jdbc;
    this.transaction = new TransactionTemplate(transactions);
    this.origin = origin.split("#", 2)[0];
    this.encryptionKey = encryptionKey; this.attemptLifetime = attemptLifetime;
  }

  /** {@code binding} is the browser-binding cookie value to set; it is never shown to scripts. */
  public record Started(String authorizationUrl, String binding) {
    @Override public String toString() { return "Started[redacted]"; }
  }

  public record Completed(BuyerAccountService.Session session, SocialProvider provider) {
    @Override public String toString() { return "Completed[redacted]"; }
  }

  /** The providers this deployment offers, in a stable order. */
  public List<SocialProvider> providers() {
    if (!keyConfigured()) return List.of();
    return Arrays.stream(SocialProvider.values()).filter(configuration.providers::containsKey).toList();
  }

  Duration attemptLifetime() { return attemptLifetime; }

  /**
   * Stores a single-use attempt and returns the provider's consent page. An existing well-formed binding cookie is
   * reused, so attempts started in two tabs of one browser both remain usable.
   */
  public Started start(SocialProvider provider, String existingBinding) {
    var client = client(provider);
    String binding = existingBinding != null && existingBinding.matches("[A-Za-z0-9_-]{43}") ? existingBinding : secret();
    String state = secret(), verifier = secret(), nonce = secret(), stateHash = stateHash(state);
    Instant now = PersistedInstant.round(Instant.now());
    transaction.executeWithoutResult(status -> {
      jdbc.update("delete from buyer_account_social_sign_in_attempts where expires_at <= ?", Timestamp.from(now));
      jdbc.update("""
          insert into buyer_account_social_sign_in_attempts
            (state_hash, provider, binding_hash, verifier_ciphertext, nonce_ciphertext, created_at, expires_at)
          values (?, ?, ?, ?, ?, ?, ?)
          """, stateHash, provider.name(), bindingHash(binding), encrypt(stateHash, VERIFIER, verifier),
          encrypt(stateHash, NONCE, nonce), Timestamp.from(now), Timestamp.from(now.plus(attemptLifetime)));
    });
    return new Started(client.authorizationUrl(state, verifier, nonce, configuration.redirectUri(provider)), binding);
  }

  /**
   * Records the provider's code against its attempt and returns where to send the browser. The callback never signs
   * anyone in: the binding cookie is checked when the Buyer's page completes the attempt.
   */
  public String callback(SocialProvider provider, String state, String code, String error) {
    boolean failedConsent = error != null && !error.isBlank();
    String outcome = failedConsent && CANCELLED.contains(error) ? "cancelled" : "failed";
    if (state == null || state.isBlank() || state.length() > 200) return failure(outcome);
    String hash = stateHash(state);
    boolean stored = Boolean.TRUE.equals(transaction.execute(status -> {
      var rows = jdbc.query("select provider, code_ciphertext, expires_at, consumed_at from buyer_account_social_sign_in_attempts where state_hash = ?",
          (row, index) -> new Object[] {row.getString(1), row.getString(2), row.getTimestamp(3).toInstant(), row.getTimestamp(4)}, hash);
      if (rows.isEmpty() || rows.getFirst()[3] != null) return false;
      var attempt = rows.getFirst();
      boolean usable = !failedConsent && provider.name().equals(attempt[0]) && attempt[1] == null
          && ((Instant) attempt[2]).isAfter(Instant.now()) && code != null && !code.isBlank() && code.length() <= 4096;
      if (!usable) {
        consume(hash);
        return false;
      }
      return jdbc.update("update buyer_account_social_sign_in_attempts set code_ciphertext = ? "
          + "where state_hash = ? and code_ciphertext is null and consumed_at is null", encrypt(hash, CODE, code), hash) == 1;
    }));
    return stored ? origin + "#social-sign-in=" + state : failure(outcome);
  }

  /**
   * Finishes an attempt from the Buyer's page. The binding cookie must match, and the attempt must be unexpired,
   * unused and hold a code; it is consumed either way. Every refusal is the same outcome.
   */
  public Completed complete(String state, String binding) {
    if (state == null || state.isBlank() || state.length() > 200) throw failed(null);
    record Pending(SocialProvider provider, String verifier, String nonce, String code) {
      @Override public String toString() { return "Pending[redacted]"; }
    }
    record Consumed(SocialProvider provider, Pending pending) {}
    String hash = stateHash(state);
    Consumed consumed = transaction.execute(status -> {
      var rows = jdbc.query("select provider, binding_hash, verifier_ciphertext, nonce_ciphertext, code_ciphertext, expires_at "
          + "from buyer_account_social_sign_in_attempts where state_hash = ?", (row, index) -> new Object[] {
              row.getString(1), row.getString(2), row.getString(3), row.getString(4), row.getString(5),
              row.getTimestamp(6).toInstant()}, hash);
      if (rows.isEmpty()) return null;
      var row = rows.getFirst();
      var provider = SocialProvider.valueOf((String) row[0]);
      // Consuming decides a race between two completions of one state: only one of them marks the row.
      if (!consume(hash)) return new Consumed(provider, null);
      boolean bound = binding != null && !binding.isBlank() && binding.length() <= 100
          && MessageDigest.isEqual(bindingHash(binding).getBytes(StandardCharsets.UTF_8), ((String) row[1]).getBytes(StandardCharsets.UTF_8));
      if (!bound || row[4] == null || !((Instant) row[5]).isAfter(Instant.now())) return new Consumed(provider, null);
      return new Consumed(provider, new Pending(provider, decrypt(hash, VERIFIER, (String) row[2]),
          decrypt(hash, NONCE, (String) row[3]), decrypt(hash, CODE, (String) row[4])));
    });
    if (consumed == null) throw failed(null);
    var provider = consumed.provider();
    var pending = consumed.pending();
    if (pending == null) throw failed(provider);
    var client = configuration.providers.get(provider);
    if (client == null) throw failed(provider);
    SocialIdentityProvider.Identity identity;
    try {
      identity = client.exchange(pending.code(), pending.verifier(), pending.nonce(), configuration.redirectUri(provider));
    } catch (SocialIdentityProvider.Failure failure) {
      if (failure.kind == SocialIdentityProvider.Failure.Kind.UNAVAILABLE)
        throw new AccountProblem(HttpStatus.SERVICE_UNAVAILABLE, "SOCIAL_SIGN_IN_UNAVAILABLE", provider.label()
            + " could not be reached, so you are not signed in. Try again or continue with your email.");
      throw failed(provider);
    }
    var session = transaction.execute(status -> signIn(provider, identity));
    return new Completed(session, provider);
  }

  /** Linked providers and link dates for the account export; never the subject. */
  @Transactional(readOnly = true)
  public List<Map<String, Object>> exportForAccount(String accountId) {
    return jdbc.query("select provider, linked_at from buyer_account_identities where account_id = ? order by linked_at, provider",
        (row, index) -> {
          var exported = new LinkedHashMap<String, Object>();
          exported.put("provider", row.getString(1));
          exported.put("linkedAt", row.getTimestamp(2).toInstant());
          return (Map<String, Object>) exported;
        }, accountId);
  }

  // ---------------------------------------------------------------- internals

  private BuyerAccountService.Session signIn(SocialProvider provider, SocialIdentityProvider.Identity identity) {
    String subjectHash = secrets.protectAccountSecret("social-subject", provider.name() + ":" + identity.subject());
    var linked = linkedAccount(provider, subjectHash);
    if (linked != null) {
      var account = owners.findLockedById(linked).orElseThrow(() -> failed(provider));
      if (!"ACTIVE".equals(account.lifecycle)) throw failed(provider);
      return accounts.issueSession(account.id, provider.name());
    }
    // Apple private-relay addresses are ordinary addresses here.
    String normalized = identity.email().strip().toLowerCase(Locale.ROOT);
    String lookup = secrets.protectAccountSecret("email", normalized);
    var account = owners.findLockedByEmailLookup(lookup).orElse(null);
    boolean created = false;
    if (account == null) {
      try { enrollment.create(lookup, normalized); created = true; }
      catch (org.springframework.dao.DataIntegrityViolationException concurrentlyCreated) { /* Read the winner's row below. */ }
      account = owners.findLockedByEmailLookup(lookup).orElseThrow(() -> failed(provider));
    }
    if (!"ACTIVE".equals(account.lifecycle)) throw failed(provider);
    // Re-read under the account lock: a concurrent first sign-in with this identity may have linked it already.
    linked = linkedAccount(provider, subjectHash);
    if (linked != null && !linked.equals(account.id)) throw failed(provider);
    Instant now = Instant.now();
    if (account.verifiedAt == null) { account.verifiedAt = now; account.updatedAt = now; }
    if (linked == null) {
      jdbc.update("insert into buyer_account_identities (id, account_id, provider, subject_hash, linked_at) values (?, ?, ?, ?, ?)",
          UUID.randomUUID().toString(), account.id, provider.name(), subjectHash, Timestamp.from(now));
      if (!created) event(account.id, "SOCIAL_SIGN_IN_LINKED");
    }
    return accounts.issueSession(account.id, provider.name());
  }

  /** Marks an attempt used and erases its secrets; true for the one caller that consumed it. */
  private boolean consume(String stateHash) {
    return jdbc.update("update buyer_account_social_sign_in_attempts set consumed_at = ?, verifier_ciphertext = '', "
        + "nonce_ciphertext = '', code_ciphertext = null where state_hash = ? and consumed_at is null",
        Timestamp.from(Instant.now()), stateHash) == 1;
  }

  private String linkedAccount(SocialProvider provider, String subjectHash) {
    return jdbc.query("select account_id from buyer_account_identities where provider = ? and subject_hash = ?",
        (row, index) -> row.getString(1), provider.name(), subjectHash).stream().findFirst().orElse(null);
  }

  private void event(String owner, String type) {
    String id = UUID.randomUUID().toString();
    jdbc.update("insert into buyer_account_security_events (id, account_id, event_type, created_at) values (?, ?, ?, ?)",
        id, owner, type, Timestamp.from(Instant.now()));
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override public void afterCommit() { notifications.notify(owner, NotificationType.ACCOUNT_SECURITY, type + ":" + id); }
    });
  }

  private SocialIdentityProvider client(SocialProvider provider) {
    var client = configuration.providers.get(provider);
    if (client == null || !keyConfigured()) throw new AccountProblem(HttpStatus.NOT_FOUND, "SOCIAL_SIGN_IN_UNAVAILABLE",
        "Sign-in with " + provider.label() + " is not available. Continue with your email.");
    return client;
  }

  private String failure(String outcome) { return origin + "#social-sign-in-error=" + outcome; }

  static AccountProblem failed(SocialProvider provider) {
    String name = provider == null ? "Google or Apple" : provider.label();
    return new AccountProblem(HttpStatus.BAD_REQUEST, "SOCIAL_SIGN_IN_FAILED",
        "Sign-in with " + name + " could not be completed. Try again or continue with your email.");
  }

  private String stateHash(String state) { return secrets.protectAccountSecret("social-sign-in-state", state); }
  private String bindingHash(String binding) { return secrets.protectAccountSecret("social-sign-in-binding", binding); }

  private String secret() {
    byte[] bytes = new byte[32]; random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  // The verifier, nonce and code are AES-GCM encrypted under a key derived for this purpose from the account
  // data key, and bound to their attempt and purpose.

  private boolean keyConfigured() { return encryptionKey.getBytes(StandardCharsets.UTF_8).length >= 32; }

  private String encrypt(String stateHash, String purpose, String value) {
    byte[] iv = new byte[12]; random.nextBytes(iv);
    byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, stateHash, purpose, iv, value.getBytes(StandardCharsets.UTF_8));
    byte[] stored = Arrays.copyOf(iv, iv.length + encrypted.length);
    System.arraycopy(encrypted, 0, stored, iv.length, encrypted.length);
    return Base64.getEncoder().encodeToString(stored);
  }

  private String decrypt(String stateHash, String purpose, String stored) {
    byte[] bytes = Base64.getDecoder().decode(stored);
    if (bytes.length < 28) throw new IllegalStateException("Stored sign-in attempt is unreadable");
    return new String(crypt(Cipher.DECRYPT_MODE, stateHash, purpose, Arrays.copyOf(bytes, 12),
        Arrays.copyOfRange(bytes, 12, bytes.length)), StandardCharsets.UTF_8);
  }

  private byte[] crypt(int mode, String stateHash, String purpose, byte[] iv, byte[] content) {
    if (!keyConfigured()) throw new AccountProblem(HttpStatus.SERVICE_UNAVAILABLE, "ACCOUNTS_UNAVAILABLE",
        "Buyer Accounts are unavailable right now. Your Buying Case still works without one.");
    try {
      byte[] key = MessageDigest.getInstance("SHA-256")
          .digest(("buyer-account-social-sign-in.v1:" + encryptionKey).getBytes(StandardCharsets.UTF_8));
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
      cipher.updateAAD((stateHash + ":" + purpose).getBytes(StandardCharsets.UTF_8));
      return cipher.doFinal(content);
    } catch (GeneralSecurityException failure) {
      // The cause is dropped: it could carry key material into a log.
      throw new IllegalStateException("Sign-in attempt protection failed");
    }
  }
}

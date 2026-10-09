package com.dealanalyzer.account;

import com.dealanalyzer.notification.BuyerNotifications;
import com.dealanalyzer.notification.NotificationType;
import com.yubico.webauthn.*;
import com.yubico.webauthn.data.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** The library verifies WebAuthn; this boundary owns attempts, account lifecycle and serialization. */
@Service
public class PasskeyService {
  private final JdbcTemplate jdbc;
  private final BuyerAccountService accounts;
  private final BuyerAccountRepository owners;
  private final BuyerNotifications notifications;
  private final PasskeyConfiguration configuration;
  private final RelyingParty rp;

  PasskeyService(JdbcTemplate jdbc, BuyerAccountService accounts, BuyerAccountRepository owners,
      BuyerNotifications notifications, PasskeyConfiguration configuration) {
    this.jdbc = jdbc; this.accounts = accounts; this.owners = owners;
    this.notifications = notifications; this.configuration = configuration;
    rp = RelyingParty.builder().identity(RelyingPartyIdentity.builder().id(configuration.rpId).name("otdog").build())
        .credentialRepository(new Credentials()).origins(configuration.origins)
        .allowOriginPort(false).allowOriginSubdomain(false)
        // Inspect the library's counter verdict only after successful signature verification, so
        // a forged assertion cannot generate a security event for another Buyer.
        .validateSignatureCounter(false).build();
  }

  public record Options(String attemptId, Object publicKey) {}
  public record Metadata(String id, String nickname, Instant createdAt, Instant lastUsedAt) {}
  private record Credential(String id, String owner, String credentialId, String key, long count,
      boolean eligible, boolean backedUp) {}
  private record Attempt(String owner, String sessionHash, String options) {}

  @Transactional
  public Options startRegistration(String token) {
    var session = registrationSession(token);
    if (jdbc.queryForObject("select count(*) from buyer_account_passkeys where account_id = ?", Long.class, session.accountId) >= 20)
      throw new AccountProblem(HttpStatus.CONFLICT, "PASSKEY_LIMIT", "Remove a passkey before adding another.");
    var options = rp.startRegistration(StartRegistrationOptions.builder()
        .user(UserIdentity.builder().name(session.accountId).displayName("Buyer Account")
            .id(handle(session.accountId)).build())
        .authenticatorSelection(AuthenticatorSelectionCriteria.builder().residentKey(ResidentKeyRequirement.REQUIRED)
            .userVerification(UserVerificationRequirement.REQUIRED).build())
        .timeout(configuration.lifetime.toMillis()).build());
    try {
      return store(session.accountId, session.tokenHash, "REGISTER", options.toJson(), options.toCredentialsCreateJson());
    } catch (java.io.IOException failure) { throw new IllegalStateException("Passkey options unavailable", failure); }
  }

  @Transactional(noRollbackFor = AccountProblem.class)
  public List<Metadata> finishRegistration(String token, String attemptId, String nickname, String response) {
    var session = registrationSession(token);
    var attempt = consume(attemptId, "REGISTER", session.accountId, session.tokenHash);
    try {
      var credential = PublicKeyCredential.parseRegistrationResponseJson(response);
      if (!"none".equals(credential.getResponse().getAttestation().getFormat())) throw refused();
      var result = rp.finishRegistration(FinishRegistrationOptions.builder()
          .request(PublicKeyCredentialCreationOptions.fromJson(attempt.options))
          .response(credential).build());
      if (jdbc.queryForObject("select count(*) from buyer_account_passkeys where credential_id = ?", Long.class,
          result.getKeyId().getId().getBase64Url()) != 0
          || jdbc.queryForObject("select count(*) from buyer_account_passkeys where account_id = ?", Long.class, session.accountId) >= 20)
        throw refused();
      String id = UUID.randomUUID().toString();
      jdbc.update("insert into buyer_account_passkeys (id, account_id, credential_id, public_key_cose, nickname, signature_count, backup_eligible, backup_state, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
          id, session.accountId, result.getKeyId().getId().getBase64Url(), result.getPublicKeyCose().getBase64Url(),
          nickname.strip(), result.getSignatureCount(), result.isBackupEligible(), result.isBackedUp(), Timestamp.from(Instant.now()));
      afterCommit(() -> notifications.notify(session.accountId, NotificationType.ACCOUNT_SECURITY, "passkey-added:" + id));
      return metadata(session.accountId);
    } catch (AccountProblem problem) { throw problem; }
    catch (Exception invalid) { throw refused(); }
  }

  @Transactional
  public Options startSignIn() {
    var options = rp.startAssertion(StartAssertionOptions.builder().userVerification(UserVerificationRequirement.REQUIRED)
        .timeout(configuration.lifetime.toMillis()).build());
    try { return store(null, null, "SIGN_IN", options.toJson(), options.toCredentialsGetJson()); }
    catch (java.io.IOException failure) { throw new IllegalStateException("Passkey options unavailable", failure); }
  }

  @Transactional(noRollbackFor = AccountProblem.class)
  public BuyerAccountService.Session finishSignIn(String attemptId, String response) {
    var attempt = consume(attemptId, "SIGN_IN", null, null);
    try {
      var assertion = PublicKeyCredential.parseAssertionResponseJson(response);
      var credential = find(assertion.getId().getBase64Url()).orElseThrow(PasskeyService::refused);
      // Same account-first lock order as deletion and removal. Re-read after the lock: a credential
      // removed during this attempt can never create a session. Concurrent assertions serialize.
      lockActive(credential.owner);
      credential = find(assertion.getId().getBase64Url()).orElseThrow(PasskeyService::refused);
      var result = rp.finishAssertion(FinishAssertionOptions.builder().request(AssertionRequest.fromJson(attempt.options))
          .response(assertion).build());
      if (!result.isSuccess() || !result.getUsername().equals(credential.owner)) throw refused();
      if (!result.isSignatureCounterValid()) {
        jdbc.update("insert into buyer_account_security_events (id, account_id, event_type, created_at) values (?, ?, ?, ?)",
            UUID.randomUUID().toString(), credential.owner, "PASSKEY_COUNTER_REGRESSION", Timestamp.from(Instant.now()));
        throw refused();
      }
      jdbc.update("update buyer_account_passkeys set signature_count = ?, backup_state = ?, last_used_at = ? where id = ?",
          result.getSignatureCount(), result.isBackedUp(), Timestamp.from(Instant.now()), credential.id);
      return accounts.issueSession(credential.owner, "PASSKEY");
    } catch (AccountProblem problem) { throw problem; }
    catch (Exception invalid) { throw refused(); }
  }

  @Transactional(readOnly = true)
  public List<Metadata> list(String token) { return metadata(activeSession(token).accountId); }

  public List<Metadata> metadata(String owner) {
    return jdbc.query("select id, nickname, created_at, last_used_at from buyer_account_passkeys where account_id = ? order by created_at, id",
        (row, index) -> new Metadata(row.getString(1), row.getString(2), row.getTimestamp(3).toInstant(),
            row.getTimestamp(4) == null ? null : row.getTimestamp(4).toInstant()), owner);
  }

  @Transactional
  public List<Metadata> remove(String token, String id) {
    var session = activeSession(token); lockActive(session.accountId);
    if (jdbc.update("delete from buyer_account_passkeys where id = ? and account_id = ?", id, session.accountId) != 1)
      throw new AccountProblem(HttpStatus.NOT_FOUND, "PASSKEY_UNKNOWN", "This passkey is unavailable.");
    return metadata(session.accountId);
  }

  private AccountSessionEntity activeSession(String token) {
    var session = accounts.requireSession(token);
    var owner = owners.findById(session.accountId).orElseThrow(BuyerAccountService::sessionEnded);
    if (!"ACTIVE".equals(owner.lifecycle)) throw BuyerAccountService.sessionEnded();
    return session;
  }
  private AccountSessionEntity registrationSession(String token) {
    var session = activeSession(token);
    if ("PASSWORD".equals(session.signInMethod))
      throw new AccountProblem(HttpStatus.FORBIDDEN, "PASSKEY_STEP_UP",
          "Sign in with an email link or existing passkey before adding a passkey.");
    lockActive(session.accountId);
    // Re-read database state after the account lock, rather than relying on a cached JPA row.
    if (jdbc.queryForObject("select count(*) from buyer_account_sessions where token_hash = ? and revoked_at is null and expires_at > ?",
        Long.class, session.tokenHash, Timestamp.from(Instant.now())) != 1) throw BuyerAccountService.sessionEnded();
    return session;
  }
  private void lockActive(String id) {
    var owner = owners.findLockedById(id).orElseThrow(PasskeyService::refused);
    if (!"ACTIVE".equals(owner.lifecycle)) throw refused();
  }
  private Options store(String owner, String sessionHash, String ceremony, String stored, String browser) throws java.io.IOException {
    String id = UUID.randomUUID().toString();
    // Expired attempts carry no useful recovery data. Clean them on issuance and in the account worker.
    jdbc.update("delete from buyer_account_passkey_challenges where expires_at <= ?", Timestamp.from(Instant.now()));
    jdbc.update("insert into buyer_account_passkey_challenges (id, account_id, session_hash, ceremony, options_json, expires_at) values (?, ?, ?, ?, ?, ?)",
        id, owner, sessionHash, ceremony, stored, Timestamp.from(Instant.now().plus(configuration.lifetime)));
    return new Options(id, new com.fasterxml.jackson.databind.ObjectMapper().readTree(browser).get("publicKey"));
  }
  private Attempt consume(String id, String ceremony, String owner, String sessionHash) {
    var attempts = jdbc.query("select account_id, session_hash, options_json from buyer_account_passkey_challenges where id = ? and ceremony = ?",
        (row, index) -> new Attempt(row.getString(1), row.getString(2), row.getString(3)), id, ceremony);
    if (attempts.isEmpty()) throw refused();
    var attempt = attempts.getFirst();
    if (!Objects.equals(owner, attempt.owner) || !Objects.equals(sessionHash, attempt.sessionHash)) throw refused();
    Instant now = Instant.now();
    if (jdbc.update("update buyer_account_passkey_challenges set consumed_at = ? where id = ? and consumed_at is null and expires_at > ?",
        Timestamp.from(now), id, Timestamp.from(now)) != 1) throw refused();
    return attempt;
  }
  private Optional<Credential> find(String id) {
    return jdbc.query("select id, account_id, credential_id, public_key_cose, signature_count, backup_eligible, backup_state from buyer_account_passkeys where credential_id = ?",
        (row, index) -> new Credential(row.getString(1), row.getString(2), row.getString(3), row.getString(4),
            row.getLong(5), row.getBoolean(6), row.getBoolean(7)), id).stream().findFirst();
  }
  private static ByteArray handle(String id) { return new ByteArray(id.getBytes(StandardCharsets.UTF_8)); }
  private static ByteArray decode(String value) {
    try { return ByteArray.fromBase64Url(value); } catch (Exception invalid) { throw refused(); }
  }
  private static AccountProblem refused() {
    return new AccountProblem(HttpStatus.BAD_REQUEST, "PASSKEY_REFUSED", "This passkey attempt could not be verified. Try again or use an email link.");
  }
  private static void afterCommit(Runnable action) {
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override public void afterCommit() { action.run(); }
    });
  }

  private class Credentials implements CredentialRepository {
    @Override public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(String username) {
      return jdbc.query("select credential_id from buyer_account_passkeys where account_id = ?",
          (row, index) -> PublicKeyCredentialDescriptor.builder().id(decode(row.getString(1))).build(), username)
          .stream().collect(java.util.stream.Collectors.toSet());
    }
    @Override public Optional<ByteArray> getUserHandleForUsername(String username) {
      return owners.findById(username).map(owner -> handle(owner.id));
    }
    @Override public Optional<String> getUsernameForUserHandle(ByteArray userHandle) {
      String id = new String(userHandle.getBytes(), StandardCharsets.UTF_8);
      return owners.findById(id).filter(owner -> "ACTIVE".equals(owner.lifecycle)).map(owner -> owner.id);
    }
    @Override public Optional<RegisteredCredential> lookup(ByteArray credentialId, ByteArray userHandle) {
      return find(credentialId.getBase64Url()).filter(row -> handle(row.owner).equals(userHandle)).map(this::registered);
    }
    @Override public Set<RegisteredCredential> lookupAll(ByteArray credentialId) {
      return find(credentialId.getBase64Url()).map(row -> Set.of(registered(row))).orElse(Set.of());
    }
    private RegisteredCredential registered(Credential row) {
      return RegisteredCredential.builder().credentialId(decode(row.credentialId)).userHandle(handle(row.owner))
          .publicKeyCose(decode(row.key)).signatureCount(row.count).backupEligible(row.eligible).backupState(row.backedUp).build();
    }
  }
}

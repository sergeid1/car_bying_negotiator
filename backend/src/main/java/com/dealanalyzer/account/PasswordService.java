package com.dealanalyzer.account;

import com.dealanalyzer.buyingcase.CaseAccessService;
import com.dealanalyzer.notification.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;

@Service
public class PasswordService {
  private final BuyerAccountService accounts;
  private final BuyerAccountRepository owners;
  private final AccountSessionRepository sessions;
  private final CaseAccessService secrets;
  private final BreachedPasswords breaches;
  private final PasswordAttempts attempts;
  private final BuyerNotifications notifications;
  private final JdbcTemplate jdbc;
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
  private final String dummy = encoder.encode(UUID.randomUUID().toString());

  PasswordService(BuyerAccountService accounts, BuyerAccountRepository owners, AccountSessionRepository sessions,
      CaseAccessService secrets, BreachedPasswords breaches, PasswordAttempts attempts, BuyerNotifications notifications,
      JdbcTemplate jdbc) {
    this.accounts = accounts; this.owners = owners; this.sessions = sessions; this.secrets = secrets;
    this.breaches = breaches; this.attempts = attempts; this.notifications = notifications; this.jdbc = jdbc;
  }
  public record Status(boolean configured) {}

  @Transactional(readOnly = true)
  public Status status(String token) {
    var session = accounts.requireSession(token);
    var owner = owners.findById(session.accountId).orElseThrow(BuyerAccountService::sessionEnded);
    if (!"ACTIVE".equals(owner.lifecycle)) throw BuyerAccountService.sessionEnded();
    return new Status(owner.passwordHash != null);
  }

  @Transactional
  public Status set(String token, String password) {
    var session = recentSession(token);
    if (!valid(password)) throw new AccountProblem(HttpStatus.BAD_REQUEST, "PASSWORD_LENGTH",
        "Use at least 12 characters and at most 72 UTF-8 bytes. No special character rules apply.");
    if (breaches.contains(password)) throw new AccountProblem(HttpStatus.BAD_REQUEST, "PASSWORD_BREACHED",
        "This password appears in known data breaches. Choose another password.");
    var owner = owners.findLockedById(session.accountId).orElseThrow(BuyerAccountService::sessionEnded);
    requireLive(session, owner);
    boolean changing = owner.passwordHash != null;
    owner.passwordHash = encoder.encode(password); owner.updatedAt = Instant.now();
    revokeOthers(session);
    event(session.accountId, changing ? "PASSWORD_CHANGED" : "PASSWORD_SET");
    return new Status(true);
  }

  @Transactional
  public Status remove(String token) {
    var session = recentSession(token);
    var owner = owners.findLockedById(session.accountId).orElseThrow(BuyerAccountService::sessionEnded);
    requireLive(session, owner);
    if (owner.passwordHash != null) {
      owner.passwordHash = null; owner.updatedAt = Instant.now(); revokeOthers(session);
      event(session.accountId, "PASSWORD_REMOVED");
    }
    return new Status(false);
  }

  @Transactional
  public BuyerAccountService.Session signIn(String email, String password, String address) {
    String normalized = email.strip().toLowerCase(Locale.ROOT);
    if (!attempts.acquire(normalized, address)) throw new AccountProblem(HttpStatus.TOO_MANY_REQUESTS,
        "PASSWORD_SIGN_IN_PAUSED", "Sign-in could not be completed. Wait before retrying or use an email link.");
    var owner = owners.findLockedByEmailLookup(secrets.protectAccountSecret("email", normalized)).orElse(null);
    boolean eligible = owner != null && "ACTIVE".equals(owner.lifecycle) && owner.passwordHash != null;
    // One cost-12 verification for unknown accounts, accounts without passwords and wrong passwords.
    boolean bounded = valid(password);
    boolean matched = encoder.matches(bounded ? password : "invalid-password", eligible ? owner.passwordHash : dummy);
    if (!bounded || !eligible || !matched) throw new AccountProblem(HttpStatus.UNAUTHORIZED,
        "PASSWORD_SIGN_IN_FAILED", "Sign-in could not be completed. Check your details or use an email link.");
    return accounts.issueSession(owner.id, "PASSWORD");
  }

  private AccountSessionEntity recentSession(String token) {
    var session = accounts.requireSession(token);
    if (!session.createdAt.plus(Duration.ofMinutes(10)).isAfter(Instant.now()))
      throw new AccountProblem(HttpStatus.FORBIDDEN, "RECENT_SIGN_IN_REQUIRED",
          "Sign in again with an email link, passkey or password before changing your password.");
    return session;
  }
  private void requireLive(AccountSessionEntity session, BuyerAccountEntity owner) {
    if (!"ACTIVE".equals(owner.lifecycle) || jdbc.queryForObject(
        "select count(*) from buyer_account_sessions where token_hash = ? and revoked_at is null and expires_at > ?",
        Long.class, session.tokenHash, Timestamp.from(Instant.now())) != 1) throw BuyerAccountService.sessionEnded();
  }
  private void revokeOthers(AccountSessionEntity session) {
    for (var other : sessions.findByAccountIdOrderByCreatedAt(session.accountId))
      if (!other.tokenHash.equals(session.tokenHash) && other.revokedAt == null) other.revokedAt = Instant.now();
  }
  private void event(String owner, String type) {
    String id = UUID.randomUUID().toString();
    jdbc.update("insert into buyer_account_security_events (id, account_id, event_type, created_at) values (?, ?, ?, ?)",
        id, owner, type, Timestamp.from(Instant.now()));
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override public void afterCommit() { notifications.notify(owner, NotificationType.ACCOUNT_SECURITY, type + ":" + id); }
    });
  }
  private static boolean valid(String password) {
    return password != null && password.codePointCount(0, password.length()) >= 12
        && password.getBytes(StandardCharsets.UTF_8).length <= 72;
  }
}

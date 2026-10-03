package com.dealanalyzer.account;

import com.dealanalyzer.buyingcase.CaseAccessService;
import com.dealanalyzer.buyingcase.persistence.AccountCaseLifecycle;
import com.dealanalyzer.recovery.RecoveryEmail;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Passwordless Buyer Accounts. Contact details are the Buyer's only: they are never logged, never
 * sent to analytics or operator tooling, and no endpoint releases them to anyone but the Buyer.
 */
@Service
public class BuyerAccountService {
  static final String SESSION_ENDED = "Your sign-in has ended. Request a new sign-in link.";

  private final BuyerAccountRepository accounts;
  private final SignInLinkRepository links;
  private final AccountSessionRepository sessions;
  private final AccountCaseRepository attachments;
  private final ContactProtection protection;
  private final CaseAccessService secrets;
  private final AccountCaseLifecycle caseLifecycle;
  private final RecoveryEmail email;
  private final String origin;
  private final Duration linkLifetime;
  private final Duration sessionLifetime;

  BuyerAccountService(BuyerAccountRepository accounts, SignInLinkRepository links, AccountSessionRepository sessions,
      AccountCaseRepository attachments, ContactProtection protection, CaseAccessService secrets,
      AccountCaseLifecycle caseLifecycle, RecoveryEmail email,
      @Value("${case.recovery.public-url:http://localhost:5173/}") String origin,
      @Value("${account.sign-in.link-lifetime:PT15M}") Duration linkLifetime,
      @Value("${account.session.lifetime:PT336H}") Duration sessionLifetime) {
    this.accounts = accounts; this.links = links; this.sessions = sessions; this.attachments = attachments;
    this.protection = protection; this.secrets = secrets; this.caseLifecycle = caseLifecycle; this.email = email;
    this.origin = origin; this.linkLifetime = linkLifetime; this.sessionLifetime = sessionLifetime;
  }

  public record Session(String sessionToken, Instant expiresAt) {}
  public record AttachedCase(String caseId, Instant attachedAt, String state) {}
  public record AccountView(Contact contact, Instant createdAt, Instant sessionExpiresAt, List<AttachedCase> cases) {}

  /** Creates the account on first request; either way, emails a single-use sign-in link. */
  @Transactional
  public void requestSignIn(String address, String requestKey) {
    String normalized = address.strip().toLowerCase(Locale.ROOT);
    String lookup = secrets.protectAccountSecret("email", normalized);
    var account = accounts.findLockedByEmailLookup(lookup).orElse(null);
    Instant now = Instant.now();
    if (account == null) {
      account = new BuyerAccountEntity(UUID.randomUUID().toString());
      account.emailLookup = lookup; account.lifecycle = "ACTIVE"; account.createdAt = now; account.updatedAt = now;
      account.contactCiphertext = protection.encrypt(account.id, new Contact(normalized, null, null, null));
      accounts.saveAndFlush(account);
    }
    // An account awaiting deletion cannot sign in; the acknowledgement stays the same.
    if (!"ACTIVE".equals(account.lifecycle)) return;
    String keyHash = secrets.protectAccountSecret("sign-in-request", requestKey);
    if (links.findByAccountIdAndRequestKeyHash(account.id, keyHash).isPresent()) return;
    String token = secrets.issue(now).token();
    var link = new SignInLinkEntity(secrets.protectAccountSecret("sign-in", token));
    link.accountId = account.id; link.requestKeyHash = keyHash; link.createdAt = now;
    link.expiresAt = now.plus(linkLifetime);
    links.saveAndFlush(link);
    email.send(normalized, origin + "#sign-in=" + token, RecoveryEmail.Purpose.ACCOUNT_SIGN_IN);
  }

  @Transactional(noRollbackFor = AccountProblem.class)
  public Session verify(String token) {
    String hash = secrets.protectAccountSecret("sign-in", token);
    var owner = links.findProjectedByTokenHash(hash).orElseThrow(BuyerAccountService::unknownLink);
    // Lock the account before the link, the same order issuance uses.
    var account = accounts.findLockedById(owner.getAccountId()).orElseThrow(BuyerAccountService::unknownLink);
    var link = links.findLockedByTokenHash(hash).orElseThrow(BuyerAccountService::unknownLink);
    Instant now = Instant.now();
    if (!"ACTIVE".equals(account.lifecycle)) throw unknownLink();
    if (link.consumedAt != null) throw new AccountProblem(HttpStatus.GONE, "SIGN_IN_LINK_USED",
        "This sign-in link was already used. Each link works once. Request a new link.");
    if (!link.expiresAt.isAfter(now)) throw new AccountProblem(HttpStatus.GONE, "SIGN_IN_LINK_EXPIRED",
        "This sign-in link has expired. Request a new link.");
    link.consumedAt = now;
    if (account.verifiedAt == null) account.verifiedAt = now;
    String sessionToken = secrets.issue(now).token();
    var session = new AccountSessionEntity(secrets.protectAccountSecret("session", sessionToken));
    session.accountId = account.id; session.createdAt = now; session.expiresAt = now.plus(sessionLifetime);
    sessions.saveAndFlush(session);
    return new Session(sessionToken, session.expiresAt);
  }

  @Transactional(readOnly = true)
  public AccountView view(String sessionToken) {
    var session = requireSession(sessionToken);
    var account = accounts.findById(session.accountId).orElseThrow(BuyerAccountService::sessionEnded);
    return new AccountView(protection.decrypt(account.id, account.contactCiphertext), account.createdAt,
        session.expiresAt, attachedCases(account.id));
  }

  @Transactional
  public AccountView updateContact(String sessionToken, String firstName, String lastName, String phone) {
    var session = requireSession(sessionToken);
    var account = accounts.findLockedById(session.accountId).orElseThrow(BuyerAccountService::sessionEnded);
    var current = protection.decrypt(account.id, account.contactCiphertext);
    account.contactCiphertext = protection.encrypt(account.id,
        new Contact(current.email(), blankToNull(firstName), blankToNull(lastName), blankToNull(phone)));
    account.updatedAt = Instant.now();
    return view(sessionToken);
  }

  /** Attaches a case opened by its case link. The case link keeps working unchanged. */
  @Transactional
  public AccountView attach(String sessionToken, String caseId, String caseToken) {
    var session = requireSession(sessionToken);
    accounts.findLockedById(session.accountId).orElseThrow(BuyerAccountService::sessionEnded);
    caseLifecycle.requireCaseLink(caseId, caseToken);
    var existing = attachments.findById(caseId).orElse(null);
    if (existing != null && !existing.accountId.equals(session.accountId))
      throw new AccountProblem(HttpStatus.CONFLICT, "CASE_ATTACHED_ELSEWHERE",
          "This Buying Case is already attached to a different Buyer Account.");
    if (existing == null) {
      var attachment = new AccountCaseEntity(caseId);
      attachment.accountId = session.accountId; attachment.attachedAt = Instant.now();
      attachments.saveAndFlush(attachment);
    }
    return view(sessionToken);
  }

  @Transactional
  public void signOut(String sessionToken, boolean everywhere) {
    var session = requireSession(sessionToken);
    Instant now = Instant.now();
    if (!everywhere) { session.revokedAt = now; return; }
    for (var other : sessions.findByAccountIdOrderByCreatedAt(session.accountId))
      if (other.revokedAt == null) other.revokedAt = now;
  }

  AccountSessionEntity requireSession(String sessionToken) {
    if (sessionToken == null || sessionToken.isBlank() || sessionToken.length() > 128) throw sessionEnded();
    var session = sessions.findById(secrets.protectAccountSecret("session", sessionToken))
        .orElseThrow(BuyerAccountService::sessionEnded);
    if (session.revokedAt != null || !session.expiresAt.isAfter(Instant.now())) throw sessionEnded();
    return session;
  }

  List<AttachedCase> attachedCases(String accountId) {
    return attachments.findByAccountIdOrderByAttachedAt(accountId).stream()
        .map(a -> new AttachedCase(a.caseId, a.attachedAt, caseLifecycle.state(a.caseId))).toList();
  }

  private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.strip(); }
  static AccountProblem sessionEnded() { return new AccountProblem(HttpStatus.UNAUTHORIZED, "SESSION_ENDED", SESSION_ENDED); }
  private static AccountProblem unknownLink() {
    return new AccountProblem(HttpStatus.NOT_FOUND, "SIGN_IN_LINK_UNKNOWN",
        "This sign-in link is not recognized. Check that you opened the whole link, or request a new one.");
  }
}

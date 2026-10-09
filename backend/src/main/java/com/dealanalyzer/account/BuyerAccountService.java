package com.dealanalyzer.account;

import com.dealanalyzer.buyingcase.CaseAccessService;
import com.dealanalyzer.buyingcase.persistence.AccountCaseLifecycle;
import com.dealanalyzer.recovery.RecoveryEmail;
import com.dealanalyzer.recovery.RequestEmailLimits;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Buyer Accounts. Contact details are the Buyer's only: they are never logged, never
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
  private final AccountEnrollment enrollment;
  private final RecoveryEmail email;
  private final RequestEmailLimits emailLimits;
  private final String origin;
  private final Duration linkLifetime;
  private final Duration sessionLifetime;
  private final int maxActiveLinks;
  private final Duration casePassLifetime;

  BuyerAccountService(BuyerAccountRepository accounts, SignInLinkRepository links, AccountSessionRepository sessions,
      AccountCaseRepository attachments, ContactProtection protection, CaseAccessService secrets,
      AccountCaseLifecycle caseLifecycle, AccountEnrollment enrollment, RecoveryEmail email, RequestEmailLimits emailLimits,
      @Value("${case.recovery.public-url:http://localhost:5173/}") String origin,
      @Value("${account.sign-in.link-lifetime:PT15M}") Duration linkLifetime,
      @Value("${account.session.lifetime:PT336H}") Duration sessionLifetime,
      @Value("${account.sign-in.max-active-links:3}") int maxActiveLinks,
      @Value("${account.case-pass.lifetime:PT8H}") Duration casePassLifetime) {
    if (maxActiveLinks < 1) throw new IllegalArgumentException("account.sign-in.max-active-links must be positive");
    this.accounts = accounts; this.links = links; this.sessions = sessions; this.attachments = attachments;
    this.protection = protection; this.secrets = secrets; this.caseLifecycle = caseLifecycle; this.email = email;
    this.origin = origin; this.linkLifetime = linkLifetime; this.sessionLifetime = sessionLifetime;
    this.maxActiveLinks = maxActiveLinks; this.enrollment = enrollment;
    this.emailLimits = emailLimits;
    this.casePassLifetime = casePassLifetime;
  }

  public record Session(String sessionToken, Instant expiresAt) {}
  /** {@code vehicle} and {@code dealer} are null until the case knows them. */
  public record AttachedCase(String caseId, Instant attachedAt, String state, Instant startedAt, String vehicle,
      String dealer) {}
  /** A session-bound pass for one attached case; it is not a case link and ends with the session. */
  public record OpenedCase(String caseId, String accessToken, Instant accessExpiresAt) {}
  public record AccountView(Contact contact, Instant createdAt, Instant sessionExpiresAt, List<AttachedCase> cases) {}

  /** Creates the account on first request; either way, emails a single-use sign-in link. */
  @Transactional
  public void requestSignIn(String address, String requestKey) {
    String normalized = address.strip().toLowerCase(Locale.ROOT);
    String lookup = secrets.protectAccountSecret("email", normalized);
    var account = accounts.findLockedByEmailLookup(lookup).orElse(null);
    if (account == null) {
      try { enrollment.create(lookup, normalized); }
      catch (org.springframework.dao.DataIntegrityViolationException concurrentlyCreated) { /* Read the winner's row below. */ }
      account = accounts.findLockedByEmailLookup(lookup).orElseThrow();
    }
    Instant now = Instant.now();
    // An account awaiting deletion cannot sign in; the acknowledgement stays the same.
    if (!"ACTIVE".equals(account.lifecycle)) return;
    String keyHash = secrets.protectAccountSecret("sign-in-request", requestKey);
    if (links.findByAccountIdAndRequestKeyHash(account.id, keyHash).isPresent()) return;
    // Cap unused links per address so this unauthenticated endpoint cannot flood a mailbox. The
    // account row lock serializes the count; the acknowledgement does not reveal the cap.
    if (links.countByAccountIdAndConsumedAtIsNullAndExpiresAtAfter(account.id, now) >= maxActiveLinks) return;
    if (!emailLimits.acquireDelivery()) return;
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
    return issueSession(account.id);
  }

  Session issueSession(String accountId) {
    return issueSession(accountId, "EMAIL_LINK");
  }

  Session issueSession(String accountId, String method) {
    Instant now = Instant.now();
    String sessionToken = secrets.issue(now).token();
    var session = new AccountSessionEntity(secrets.protectAccountSecret("session", sessionToken));
    session.accountId = accountId; session.createdAt = now; session.expiresAt = now.plus(sessionLifetime);
    session.signInMethod = method;
    sessions.saveAndFlush(session);
    return new Session(sessionToken, session.expiresAt);
  }

  @Transactional(readOnly = true)
  public void requireMailboxStepUp(String token) {
    var session = requireSession(token);
    // Existing email/passkey sessions keep their mailbox access. Password sessions must step up.
    if ("PASSWORD".equals(session.signInMethod))
      throw new AccountProblem(HttpStatus.FORBIDDEN, "MAILBOX_STEP_UP",
          "Sign in with a fresh email link or passkey before changing your mailbox connection.");
    if (!session.createdAt.plus(Duration.ofMinutes(10)).isAfter(Instant.now()))
      throw new AccountProblem(HttpStatus.FORBIDDEN, "MAILBOX_STEP_UP",
          "Sign in with a fresh email link or passkey before changing your mailbox connection.");
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

  /**
   * Attaches a case the signed-in Buyer just created. Returns false, leaving the case unattached
   * and its case link working, when the session has ended or the account is not active.
   */
  @Transactional
  public boolean attachCreated(String sessionToken, String caseId, String caseToken) {
    AccountSessionEntity session;
    try { session = requireSession(sessionToken); } catch (AccountProblem ended) { return false; }
    var account = accounts.findLockedById(session.accountId).orElse(null);
    if (account == null || !"ACTIVE".equals(account.lifecycle)) return false;
    caseLifecycle.requireCaseLink(caseId, caseToken);
    var existing = attachments.findById(caseId).orElse(null);
    if (existing != null) return existing.accountId.equals(session.accountId);
    var attachment = new AccountCaseEntity(caseId);
    attachment.accountId = session.accountId; attachment.attachedAt = Instant.now();
    attachments.saveAndFlush(attachment);
    return true;
  }

  /**
   * Opens an attached case for this session without its case link. A case attached to another
   * account, or one that is revoked, expired or being deleted, is the same 404 outcome.
   */
  @Transactional(readOnly = true)
  public OpenedCase open(String sessionToken, String caseId) {
    var session = requireSession(sessionToken);
    var account = accounts.findById(session.accountId).orElseThrow(BuyerAccountService::sessionEnded);
    if (!"ACTIVE".equals(account.lifecycle)) throw sessionEnded();
    var attachment = attachments.findById(caseId).orElse(null);
    if (attachment == null || !attachment.accountId.equals(session.accountId)) throw caseUnavailable();
    Instant notAfter = Instant.now().plus(casePassLifetime);
    if (session.expiresAt.isBefore(notAfter)) notAfter = session.expiresAt;
    try {
      var pass = caseLifecycle.issueAccountPass(caseId, session.tokenHash, notAfter);
      return new OpenedCase(caseId, pass.token(), pass.expiresAt());
    } catch (com.dealanalyzer.buyingcase.ResourceNotFoundException unavailable) { throw caseUnavailable(); }
  }

  /**
   * The protected hash of {@code sessionToken} when it is an active session of an active account
   * that {@code caseId} is attached to; otherwise null.
   */
  @Transactional(readOnly = true)
  public String admittedSessionHash(String sessionToken, String caseId) {
    AccountSessionEntity session;
    try { session = requireSession(sessionToken); } catch (AccountProblem ended) { return null; }
    var account = accounts.findById(session.accountId).orElse(null);
    if (account == null || !"ACTIVE".equals(account.lifecycle)) return null;
    var attachment = attachments.findById(caseId).orElse(null);
    return attachment != null && attachment.accountId.equals(session.accountId) ? session.tokenHash : null;
  }

  @Transactional
  public void signOut(String sessionToken, boolean everywhere) {
    var session = requireSession(sessionToken);
    accounts.findLockedById(session.accountId).orElseThrow(BuyerAccountService::sessionEnded);
    Instant now = Instant.now();
    if (!everywhere) { session.revokedAt = now; return; }
    for (var other : sessions.findByAccountIdOrderByCreatedAt(session.accountId))
      if (other.revokedAt == null) other.revokedAt = now;
  }

  @Transactional(readOnly = true)
  public boolean sessionActive(String sessionToken) {
    try { requireSession(sessionToken); return true; }
    catch (AccountProblem ended) { return false; }
  }

  AccountSessionEntity requireSession(String sessionToken) {
    if (sessionToken == null || sessionToken.isBlank() || sessionToken.length() > 128) throw sessionEnded();
    var session = sessions.findById(secrets.protectAccountSecret("session", sessionToken))
        .orElseThrow(BuyerAccountService::sessionEnded);
    if (session.revokedAt != null || !session.expiresAt.isAfter(Instant.now())) throw sessionEnded();
    return session;
  }

  List<AttachedCase> attachedCases(String accountId) {
    var rows = attachments.findByAccountIdOrderByAttachedAt(accountId);
    var summaries = caseLifecycle.summaries(rows.stream().map(a -> a.caseId).toList());
    return rows.stream().map(a -> {
      var summary = summaries.get(a.caseId);
      return new AttachedCase(a.caseId, a.attachedAt, caseLifecycle.state(a.caseId),
          summary == null ? null : summary.startedAt(), vehicle(summary), dealer(summary));
    }).toList();
  }

  private static String vehicle(AccountCaseLifecycle.CaseSummary summary) {
    if (summary == null) return null;
    var words = new java.util.ArrayList<String>();
    if (summary.vehicleYear() != null) words.add(summary.vehicleYear().toString());
    for (var word : new String[] {summary.vehicleMake(), summary.vehicleModel(), summary.vehicleTrim()})
      if (word != null && !word.isBlank()) words.add(word.strip());
    if (!words.isEmpty()) return String.join(" ", words);
    return summary.vinLastSix() == null ? null : "VIN ending " + summary.vinLastSix();
  }

  private static String dealer(AccountCaseLifecycle.CaseSummary summary) {
    if (summary == null) return null;
    if (summary.dealerName() != null && !summary.dealerName().isBlank()) return summary.dealerName().strip();
    return summary.dealerLocation() == null ? null : "Dealer ZIP " + summary.dealerLocation();
  }

  private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.strip(); }
  private static AccountProblem caseUnavailable() {
    return new AccountProblem(HttpStatus.NOT_FOUND, "CASE_UNAVAILABLE",
        "This Buying Case cannot be opened from your account. It may have expired or be being deleted.");
  }
  static AccountProblem sessionEnded() { return new AccountProblem(HttpStatus.UNAUTHORIZED, "SESSION_ENDED", SESSION_ENDED); }
  private static AccountProblem unknownLink() {
    return new AccountProblem(HttpStatus.NOT_FOUND, "SIGN_IN_LINK_UNKNOWN",
        "This sign-in link is not recognized. Check that you opened the whole link, or request a new one.");
  }
}

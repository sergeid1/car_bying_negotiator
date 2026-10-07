package com.dealanalyzer.mailbox;

import com.dealanalyzer.account.AccountJourneyAccess;
import com.dealanalyzer.buyingcase.CaseAccessService;
import com.dealanalyzer.time.PersistedInstant;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A Buyer's Mailbox Connection (spec 2.1, AGT-12): one Gmail or Outlook account connected with a send-only
 * permission through OAuth 2.0 authorization code with PKCE and a {@code state} check. Tokens and the address are
 * encrypted with the dedicated mailbox key and never logged or exported; they are deleted on disconnect, account
 * deletion or a provider revocation error. The platform never reads the Buyer's mailbox.
 */
@Service
public class MailboxConnections {
  static final String ACCESS = "access-token";
  static final String REFRESH = "refresh-token";
  static final String ADDRESS = "address";
  static final String VERIFIER = "pkce-verifier";

  private final AccountJourneyAccess accounts;
  private final CaseAccessService secrets;
  private final MailboxTokenProtection protection;
  private final Map<MailboxProvider, MailboxSender> senders = new java.util.EnumMap<>(MailboxProvider.class);
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final String redirectUri;
  private final Duration authorizationLifetime;
  private final SecureRandom random = new SecureRandom();

  MailboxConnections(AccountJourneyAccess accounts, CaseAccessService secrets, MailboxTokenProtection protection,
      List<MailboxSender> senders, JdbcTemplate jdbc, PlatformTransactionManager transactions,
      @Value("${mailbox.redirect-uri:${case.recovery.public-url:http://localhost:5173/}}") String redirectUri,
      @Value("${mailbox.authorization-lifetime:PT10M}") Duration authorizationLifetime) {
    this.accounts = accounts; this.secrets = secrets; this.protection = protection; this.jdbc = jdbc;
    this.transaction = new TransactionTemplate(transactions);
    for (var sender : senders)
      if (this.senders.put(sender.provider(), sender) != null)
        throw new IllegalStateException("One mailbox sender per provider");
    this.redirectUri = redirectUri; this.authorizationLifetime = authorizationLifetime;
  }

  /** The connected account as the Buyer sees it: provider and address, never tokens. */
  public record ConnectionView(MailboxProvider provider, String providerLabel, String address, Instant connectedAt) {}

  /**
   * {@code available}: connecting is switched on in this deployment. {@code providers}: the ones that can be
   * connected. {@code connection}: the current connection, or null.
   */
  public record MailboxView(boolean available, List<MailboxProvider> providers, ConnectionView connection) {}

  public record Authorization(String authorizationUrl) {
    @Override public String toString() { return "Authorization[redacted]"; }
  }

  /** A connection locked for one send. */
  record Connection(String id, String accountId, MailboxProvider provider) {}

  /** Prepared outside the case lock; dispatch must still verify the connection under its locks. */
  record Sendable(String accessToken, String address) {
    @Override public String toString() { return "Sendable[redacted]"; }
  }

  @Transactional(readOnly = true)
  public MailboxView view(String session) { return view(accounts.accountId(session), true); }

  /** Starts connecting: a single-use state and PKCE verifier, and the provider's consent page to open. */
  public Authorization start(String session, MailboxProvider provider) {
    String accountId = accounts.accountId(session);
    MailboxSender sender = sender(provider);
    if (!protection.configured()) throw unavailable();
    return transaction.execute(status -> {
      accounts.lockActiveAccount(accountId);
      Instant now = PersistedInstant.round(Instant.now());
      jdbc.update("update buyer_accounts set mailbox_generation = mailbox_generation + 1 where id = ?", accountId);
      jdbc.update("delete from mailbox_authorizations where account_id = ?", accountId);
      String state = secret(), verifier = secret(), stateHash = stateHash(state);
      jdbc.update("""
          insert into mailbox_authorizations (state_hash, account_id, provider, verifier_ciphertext, created_at, expires_at)
          values (?, ?, ?, ?, ?, ?)
          """, stateHash, accountId, provider.name(), protection.encrypt(stateHash, VERIFIER, verifier),
          Timestamp.from(now), Timestamp.from(now.plus(authorizationLifetime)));
      return new Authorization(sender.authorizationUrl(state, Pkce.challenge(verifier), redirectUri));
    });
  }

  /**
   * Completes connecting with the provider's code. The state must be this account's, unexpired and unused; it is
   * consumed either way. A grant without the send permission, or with any scope beyond send and the sending
   * address (such as reading mail), is withdrawn and refused. Only the latest authorization generation may
   * persist; disconnect invalidates even an exchange already in progress. A new connection replaces an earlier one.
   */
  public MailboxView complete(String session, String state, String code) {
    if (state == null || state.isBlank() || state.length() > 200 || code == null || code.isBlank() || code.length() > 4096)
      throw authorizationUnknown();
    record Pending(String accountId, MailboxProvider provider, String verifier, long generation) {}
    Pending pending = transaction.execute(status -> {
      String accountId = accounts.accountId(session);
      accounts.lockActiveAccount(accountId);
      String hash = stateHash(state);
      var rows = jdbc.query("select provider, verifier_ciphertext, expires_at from mailbox_authorizations where state_hash = ? and account_id = ?",
          (r, n) -> new Object[] {r.getString(1), r.getString(2), r.getTimestamp(3).toInstant()}, hash, accountId);
      jdbc.update("delete from mailbox_authorizations where state_hash = ? and account_id = ?", hash, accountId);
      if (rows.isEmpty() || !((Instant) rows.getFirst()[2]).isAfter(Instant.now())) return null;
      return new Pending(accountId, MailboxProvider.valueOf((String) rows.getFirst()[0]),
          protection.decrypt(hash, VERIFIER, (String) rows.getFirst()[1]), generation(accountId));
    });
    if (pending == null) throw authorizationUnknown();
    MailboxSender sender = sender(pending.provider());
    MailboxSender.Grant grant;
    try {
      grant = sender.exchange(code, pending.verifier(), redirectUri);
    } catch (MailboxSender.MailboxFailure failure) {
      throw failure.kind() == MailboxSender.MailboxFailure.Kind.UNAVAILABLE
          ? new MailboxProblem(HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_UNAVAILABLE",
              pending.provider().label() + " could not be reached, so nothing was connected. Try again.")
          : new MailboxProblem(HttpStatus.BAD_REQUEST, "CONNECTION_REFUSED",
              pending.provider().label() + " did not confirm the connection, so nothing was connected. Try again.");
    }
    if (!pending.provider().sendOnly(grant.scopes())) {
      revokeQuietly(pending.provider(), grant.refreshToken());
      throw new MailboxProblem(HttpStatus.BAD_REQUEST, "READ_SCOPE_REFUSED", "The permission you granted would let Deal X-Ray "
          + "read your email. It never needs that, so nothing was connected. Connect again and allow sending only.");
    }
    if (!pending.provider().grantsSend(grant.scopes())) {
      revokeQuietly(pending.provider(), grant.refreshToken());
      throw new MailboxProblem(HttpStatus.BAD_REQUEST, "SEND_SCOPE_MISSING", "Sending email was not allowed, so nothing "
          + "was connected. Connect again and allow Deal X-Ray to send email.");
    }
    record Previous(MailboxProvider provider, String refresh) {}
    Previous previousRefresh;
    try {
      previousRefresh = transaction.execute(status -> {
        accounts.lockActiveAccount(pending.accountId());
        if (generation(pending.accountId()) != pending.generation()) throw authorizationUnknown();
        Previous previous = current(pending.accountId())
            .map(row -> new Previous(row.provider(), protection.decrypt(row.id(), REFRESH, row.refreshCiphertext()))).orElse(null);
        jdbc.update("delete from mailbox_connections where account_id = ?", pending.accountId());
        String id = UUID.randomUUID().toString();
        jdbc.update("""
            insert into mailbox_connections (id, account_id, provider, address_ciphertext, granted_scope, refresh_token_ciphertext,
              access_token_ciphertext, access_expires_at, connected_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, id, pending.accountId(), pending.provider().name(), protection.encrypt(id, ADDRESS, grant.address()),
            String.join(" ", new TreeSet<>(grant.scopes())), protection.encrypt(id, REFRESH, grant.refreshToken()),
            grant.accessToken() == null || grant.accessToken().isBlank() ? null : protection.encrypt(id, ACCESS, grant.accessToken()),
            grant.accessExpiresAt() == null ? null : Timestamp.from(grant.accessExpiresAt()),
            Timestamp.from(PersistedInstant.round(Instant.now())));
        return previous;
      });
    } catch (RuntimeException failure) {
      revokeQuietly(pending.provider(), grant.refreshToken());
      throw failure;
    }
    if (previousRefresh != null) revokeQuietly(previousRefresh.provider(), previousRefresh.refresh());
    return transaction.execute(status -> view(pending.accountId(), true));
  }

  /** Disconnect commits a fence against prepared sends and pending callbacks, then withdraws the grant.
   * An already-dispatched provider call holds the account lock until it returns and cannot be recalled. */
  public MailboxView disconnect(String session) {
    record Removed(String accountId, MailboxProvider provider, String refresh) {}
    Removed removed = transaction.execute(status -> {
      String accountId = accounts.accountId(session);
      accounts.lockActiveAccount(accountId);
      var row = lockedRow(accountId);
      jdbc.update("update buyer_accounts set mailbox_generation = mailbox_generation + 1 where id = ?", accountId);
      jdbc.update("delete from mailbox_authorizations where account_id = ?", accountId);
      jdbc.update("delete from mailbox_connections where account_id = ?", accountId);
      return new Removed(accountId, row.map(StoredConnection::provider).orElse(null),
          row.map(r -> protection.decrypt(r.id(), REFRESH, r.refreshCiphertext())).orElse(null));
    });
    if (removed.refresh() != null) revokeQuietly(removed.provider(), removed.refresh());
    return transaction.execute(status -> view(removed.accountId(), true));
  }

  /** The account's connection, provider and address only, for the account export. */
  @Transactional(readOnly = true)
  public List<Map<String, Object>> exportForAccount(String accountId) {
    return current(accountId).map(row -> {
      var exported = new LinkedHashMap<String, Object>();
      exported.put("provider", row.provider().name());
      exported.put("address", protection.decrypt(row.id(), ADDRESS, row.addressCiphertext()));
      exported.put("connectedAt", row.connectedAt());
      return List.<Map<String, Object>>of(exported);
    }).orElse(List.of());
  }

  // ---------------------------------------------------------------- sending (MailboxSendService)

  /** Locks the account's connection row for the duration of the caller's transaction. */
  @Transactional(propagation = Propagation.MANDATORY)
  Optional<Connection> lockForSend(String accountId) {
    return lockedRow(accountId).filter(row -> senders.containsKey(row.provider()))
        .map(row -> new Connection(row.id(), accountId, row.provider()));
  }

  MailboxSender sender(MailboxProvider provider) {
    var sender = senders.get(provider);
    if (sender == null) throw unavailable();
    return sender;
  }

  /** Called with the account and case locks held, immediately before provider dispatch. */
  boolean stillConnected(Connection connection) {
    return lockedRow(connection.accountId()).map(row -> row.id().equals(connection.id())).orElse(false);
  }

  private long generation(String accountId) {
    return jdbc.queryForObject("select mailbox_generation from buyer_accounts where id = ?", Long.class, accountId);
  }

  /**
   * A usable access token and the sending address, refreshed when it expires within a minute. Empty when the
   * Buyer disconnected meanwhile. A revocation error deletes the connection and is rethrown.
   */
  Optional<Sendable> sendable(Connection connection) {
    var row = transaction.execute(status -> stored(connection.id()));
    if (row == null) return Optional.empty();
    String address = protection.decrypt(row.id(), ADDRESS, row.addressCiphertext());
    if (row.accessCiphertext() != null && row.accessExpiresAt() != null
        && row.accessExpiresAt().isAfter(Instant.now().plusSeconds(60)))
      return Optional.of(new Sendable(protection.decrypt(row.id(), ACCESS, row.accessCiphertext()), address));
    MailboxSender.AccessToken fresh;
    try {
      fresh = sender(row.provider()).refresh(protection.decrypt(row.id(), REFRESH, row.refreshCiphertext()));
    } catch (MailboxSender.MailboxFailure failure) {
      if (failure.kind() == MailboxSender.MailboxFailure.Kind.REVOKED) delete(connection.id());
      throw failure;
    }
    int updated = transaction.execute(status -> jdbc.update(
        "update mailbox_connections set access_token_ciphertext = ?, access_expires_at = ? where id = ?",
        protection.encrypt(row.id(), ACCESS, fresh.token()), Timestamp.from(fresh.expiresAt()), row.id()));
    return updated == 1 ? Optional.of(new Sendable(fresh.token(), address)) : Optional.empty();
  }

  /** The provider refused the access token; the next send refreshes it. */
  void forgetAccessToken(String connectionId) {
    transaction.executeWithoutResult(status -> jdbc.update(
        "update mailbox_connections set access_token_ciphertext = null, access_expires_at = null where id = ?", connectionId));
  }

  /** The provider reported the grant withdrawn: its tokens are deleted. */
  void delete(String connectionId) {
    transaction.executeWithoutResult(status -> jdbc.update("delete from mailbox_connections where id = ?", connectionId));
  }

  // ---------------------------------------------------------------- internals

  private record StoredConnection(String id, MailboxProvider provider, String addressCiphertext, String refreshCiphertext,
      String accessCiphertext, Instant accessExpiresAt, Instant connectedAt) {
    @Override public String toString() { return "StoredConnection[redacted]"; }
  }

  private static final String COLUMNS = "id, provider, address_ciphertext, refresh_token_ciphertext, access_token_ciphertext, "
      + "access_expires_at, connected_at";

  private StoredConnection map(java.sql.ResultSet r) throws java.sql.SQLException {
    return new StoredConnection(r.getString(1), MailboxProvider.valueOf(r.getString(2)), r.getString(3), r.getString(4),
        r.getString(5), Optional.ofNullable(r.getTimestamp(6)).map(Timestamp::toInstant).orElse(null), r.getTimestamp(7).toInstant());
  }

  private Optional<StoredConnection> current(String accountId) {
    return jdbc.query("select " + COLUMNS + " from mailbox_connections where account_id = ?", (r, n) -> map(r), accountId)
        .stream().findFirst();
  }

  private Optional<StoredConnection> lockedRow(String accountId) {
    return jdbc.query("select " + COLUMNS + " from mailbox_connections where account_id = ? for update", (r, n) -> map(r), accountId)
        .stream().findFirst();
  }

  private StoredConnection stored(String id) {
    return jdbc.query("select " + COLUMNS + " from mailbox_connections where id = ?", (r, n) -> map(r), id)
        .stream().findFirst().orElse(null);
  }

  private MailboxView view(String accountId, boolean withConnection) {
    boolean available = !senders.isEmpty() && protection.configured();
    var providers = Arrays.stream(MailboxProvider.values()).filter(senders::containsKey).toList();
    var connection = withConnection ? current(accountId).map(row -> new ConnectionView(row.provider(), row.provider().label(),
        protection.decrypt(row.id(), ADDRESS, row.addressCiphertext()), row.connectedAt())).orElse(null) : null;
    return new MailboxView(available, available ? providers : List.of(), connection);
  }

  private void revokeQuietly(MailboxProvider provider, String refreshToken) {
    var sender = senders.get(provider);
    if (sender == null) return;
    try { sender.revoke(refreshToken); } catch (RuntimeException ignored) { /* The local tokens are already deleted. */ }
  }

  private String stateHash(String state) { return secrets.protectAccountSecret("mailbox-state", state); }

  private String secret() {
    byte[] bytes = new byte[32]; random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static MailboxProblem unavailable() {
    return new MailboxProblem(HttpStatus.SERVICE_UNAVAILABLE, "MAILBOX_UNAVAILABLE",
        "Connecting an email account is not switched on yet. Send messages from your own email app.");
  }

  private static MailboxProblem authorizationUnknown() {
    return new MailboxProblem(HttpStatus.BAD_REQUEST, "AUTHORIZATION_UNKNOWN",
        "This connection attempt expired or was already used, so nothing was connected. Start again from your account.");
  }
}

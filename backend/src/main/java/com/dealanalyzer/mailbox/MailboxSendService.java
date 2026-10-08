package com.dealanalyzer.mailbox;

import com.dealanalyzer.account.AccountJourneyAccess;
import com.dealanalyzer.account.AccountRelayAccess;
import com.dealanalyzer.relay.DealerMessageHandoff;
import com.dealanalyzer.relay.DealerMessageHandoff.MailboxOutcome;
import com.dealanalyzer.time.PersistedInstant;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * <b>Send from my email</b> (spec 2.1 section 2.1, AGT-03, AGT-11, AGT-13): sends the exact approved, gated text
 * of a handed-off dealer message through the Buyer's connected mailbox. The mandate, gate and negotiation state
 * are rechecked, the connection is locked and the per-dealer, per-case and per-account caps are applied under the
 * case lock, and the attempt is metered before the provider is contacted. Nothing is retried: an unclear outcome
 * becomes {@code DELIVERY_UNKNOWN} for the Buyer to check their Sent folder, and every refusal leaves the message
 * available on the own-email-app path.
 */
@Service
public class MailboxSendService {
  /** Attempts that may have reached a dealer count toward the caps. */
  private static final List<String> METERED = List.of("SENDING", "SENT", "DELIVERY_UNKNOWN");

  private final DealerMessageHandoff handoffs;
  private final MailboxConnections connections;
  private final AccountJourneyAccess accounts;
  private final AccountRelayAccess caseAccounts;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final int perDealer;
  private final int perCase;
  private final int perAccount;
  private final Duration window;
  private final Duration inFlight;

  MailboxSendService(DealerMessageHandoff handoffs, MailboxConnections connections, AccountJourneyAccess accounts,
      AccountRelayAccess caseAccounts, JdbcTemplate jdbc, PlatformTransactionManager transactions,
      @Value("${mailbox.caps.per-dealer:10}") int perDealer, @Value("${mailbox.caps.per-case:20}") int perCase,
      @Value("${mailbox.caps.per-account:40}") int perAccount, @Value("${mailbox.caps.window:PT24H}") Duration window,
      @Value("${mailbox.in-flight:PT2M}") Duration inFlight) {
    if (perDealer < 1 || perCase < 1 || perAccount < 1) throw new IllegalArgumentException("Mailbox caps must be positive");
    this.handoffs = handoffs; this.connections = connections; this.accounts = accounts; this.caseAccounts = caseAccounts;
    this.jdbc = jdbc; this.transaction = new TransactionTemplate(transactions);
    this.perDealer = perDealer; this.perCase = perCase; this.perAccount = perAccount; this.window = window; this.inFlight = inFlight;
  }

  record Attempt(String id, String caseToken, MailboxConnections.Connection connection, DealerMessageHandoff.MailboxClaim claim) {
    @Override public String toString() { return "Attempt[redacted]"; }
  }

  /**
   * Sends the handed-off message, or sends again one whose earlier send had an unclear outcome. Returns the
   * message as now recorded: {@code SENT}, or {@code DELIVERY_UNKNOWN}. A send that did not happen is refused
   * with a {@link MailboxProblem} saying why and offering the own-email-app path.
   */
  public DealerMessageHandoff.Handoff send(String caseId, String caseToken, String session, String messageId) {
    Attempt attempt = handoffs.claimForMailbox(caseId, caseToken, messageId, claim -> start(caseId, caseToken, session, claim));
    MailboxConnections.Connection connection = attempt.connection();
    String label = connection.provider().label();
    MailboxSender.Delivery delivery;
    var dispatched = new java.util.concurrent.atomic.AtomicBoolean();
    try {
      var sendable = connections.sendable(connection);
      if (sendable.isEmpty()) throw notConnected();
      delivery = transaction.execute(status -> {
        accounts.lockActiveAccount(connection.accountId());
        var result = handoffs.dispatchMailbox(caseId, caseToken, attempt.claim(), () -> {
          if (!connections.stillConnected(connection)) throw notConnected();
          if (count("id = ? and outcome = 'SENDING'", attempt.id()) != 1) throw superseded();
          dispatched.set(true);
          return connections.sender(connection.provider()).send(sendable.get().accessToken(), sendable.get().address(),
              new MailboxSender.OutgoingMessage(attempt.claim().dealerAddress(), attempt.claim().subject(), attempt.claim().body()));
        });
        // Commit the outcome with dispatch's locks still held, even when a call outlives inFlight.
        finish(attempt, switch (result) {
          case SENT -> "SENT";
          case UNKNOWN -> "DELIVERY_UNKNOWN";
          case RATE_LIMITED -> "RATE_LIMITED";
          default -> "REJECTED";
        }, switch (result) {
          case SENT -> MailboxOutcome.SENT;
          case UNKNOWN -> MailboxOutcome.UNKNOWN;
          default -> MailboxOutcome.NOT_SENT;
        });
        return result;
      });
    } catch (MailboxSender.MailboxFailure failure) {
      if (dispatched.get()) return finish(attempt, "DELIVERY_UNKNOWN", MailboxOutcome.UNKNOWN);
      return switch (failure.kind()) {
        case REVOKED -> notSent(attempt, "REVOKED", new MailboxProblem(HttpStatus.CONFLICT, "MAILBOX_REVOKED", label
            + " no longer allows Deal X-Ray to send from your account, so nothing was sent and the connection was removed. "
            + "Send it from your own email app, or connect again on your account page.", true));
        default -> notSent(attempt, "UNAVAILABLE", new MailboxProblem(HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_UNAVAILABLE",
            label + " could not be reached, so nothing was sent. Try again, or send it from your own email app.", true));
      };
    } catch (RuntimeException stopped) {
      if (dispatched.get()) return finish(attempt, "DELIVERY_UNKNOWN", MailboxOutcome.UNKNOWN);
      try { finish(attempt, "UNAVAILABLE", MailboxOutcome.NOT_SENT); }
      catch (RuntimeException removed) { /* Account/case deletion may have removed the handoff. */ }
      throw stopped;
    }
    return switch (delivery) {
      case SENT -> finish(attempt, "SENT", MailboxOutcome.SENT);
      case UNKNOWN -> finish(attempt, "DELIVERY_UNKNOWN", MailboxOutcome.UNKNOWN);
      case RATE_LIMITED -> notSent(attempt, "RATE_LIMITED", new MailboxProblem(HttpStatus.TOO_MANY_REQUESTS, "PROVIDER_RATE_LIMITED",
          label + " says too many messages were sent from your account recently, so nothing was sent. Try again later, "
              + "or send it from your own email app.", true));
      case AUTH_FAILED -> {
        connections.forgetAccessToken(connection.id());
        yield notSent(attempt, "REJECTED", new MailboxProblem(HttpStatus.CONFLICT, "PROVIDER_AUTH_FAILED", label
            + " did not accept the connection just now, so nothing was sent. Try again, or send it from your own email app.", true));
      }
      case REJECTED -> notSent(attempt, "REJECTED", new MailboxProblem(HttpStatus.CONFLICT, "PROVIDER_REJECTED", label
          + " did not accept this message, so nothing was sent. Send it from your own email app instead.", true));
    };
  }

  /** Metered send attempts of the account, without addresses or content, for the account export. */
  @Transactional(readOnly = true)
  public List<Map<String, Object>> exportForAccount(String accountId) {
    return jdbc.query("""
        select case_id, message_id, provider, outcome, started_at, completed_at from mailbox_sends
        where account_id = ? order by started_at, id
        """, (r, n) -> {
          var row = new LinkedHashMap<String, Object>();
          row.put("caseId", r.getString(1)); row.put("messageId", r.getString(2)); row.put("provider", r.getString(3));
          row.put("outcome", r.getString(4)); row.put("startedAt", r.getTimestamp(5).toInstant());
          row.put("completedAt", r.getTimestamp(6) == null ? null : r.getTimestamp(6).toInstant());
          return (Map<String, Object>) row;
        }, accountId);
  }

  /** Inside the case lock: the account, its connection, the caps, then the metered attempt. */
  private Attempt start(String caseId, String caseToken, String session, DealerMessageHandoff.MailboxClaim claim) {
    String accountId = accounts.accountId(session);
    if (!caseAccounts.accountId(caseId).map(accountId::equals).orElse(false))
      throw new MailboxProblem(HttpStatus.CONFLICT, "CASE_NOT_ATTACHED", "Attach this Buying Case to your account to send "
          + "from your connected email, or send it from your own email app.", true);
    var connection = connections.lockForSend(accountId).orElseThrow(MailboxSendService::notConnected);
    Instant now = PersistedInstant.round(Instant.now());
    if (count("message_id = ? and outcome = 'SENDING' and started_at > ?", claim.messageId(), Timestamp.from(now.minus(inFlight))) > 0)
      throw new MailboxProblem(HttpStatus.CONFLICT, "SEND_IN_PROGRESS", "This message is being sent now. Wait a moment, then refresh.");
    // A crashed attempt remains uncertain. Only this explicit Buyer request may supersede it.
    // The case lock prevents expiration while the previous provider call is still entered.
    jdbc.update("""
        update mailbox_sends set outcome = 'DELIVERY_UNKNOWN', completed_at = ?
        where message_id = ? and outcome = 'SENDING' and started_at <= ?
        """, Timestamp.from(now), claim.messageId(), Timestamp.from(now.minus(inFlight)));
    Timestamp since = Timestamp.from(now.minus(window));
    String hours = window.toHours() + " hours";
    if (count("account_id = ? and dealer_address_hash = ? and " + metered() + " and started_at > ?", accountId,
        claim.dealerAddressHash(), since) >= perDealer)
      throw capped("You have sent " + perDealer + " messages to this dealer from your connected email in the last " + hours
          + ", the most allowed, so nothing was sent.");
    if (count("case_id = ? and " + metered() + " and started_at > ?", caseId, since) >= perCase)
      throw capped("You have sent " + perCase + " messages for this Buying Case from your connected email in the last " + hours
          + ", the most allowed, so nothing was sent.");
    if (count("account_id = ? and " + metered() + " and started_at > ?", accountId, since) >= perAccount)
      throw capped("You have sent " + perAccount + " messages from your connected email in the last " + hours
          + ", the most allowed, so nothing was sent.");
    String id = UUID.randomUUID().toString();
    jdbc.update("""
        insert into mailbox_sends (id, account_id, case_id, message_id, dealer_address_hash, provider, outcome, started_at)
        values (?, ?, ?, ?, ?, ?, 'SENDING', ?)
        """, id, accountId, caseId, claim.messageId(), claim.dealerAddressHash(), connection.provider().name(), Timestamp.from(now));
    return new Attempt(id, caseToken, connection, claim);
  }

  DealerMessageHandoff.Handoff finish(Attempt attempt, String metered, MailboxOutcome outcome) {
    return handoffs.completeMailboxSend(attempt.claim().caseId(), attempt.caseToken(), attempt.claim(), outcome,
        () -> record(attempt, metered));
  }

  private DealerMessageHandoff.Handoff notSent(Attempt attempt, String metered, MailboxProblem problem) {
    finish(attempt, metered, MailboxOutcome.NOT_SENT);
    throw problem;
  }

  private boolean record(Attempt attempt, String outcome) {
    return jdbc.update("update mailbox_sends set outcome = ?, completed_at = ? where id = ? and outcome = 'SENDING'", outcome,
        Timestamp.from(PersistedInstant.round(Instant.now())), attempt.id()) == 1;
  }

  private static MailboxProblem superseded() {
    return new MailboxProblem(HttpStatus.CONFLICT, "SEND_SUPERSEDED", "This attempt is no longer active. Refresh to check the message.");
  }

  private long count(String predicate, Object... arguments) {
    return jdbc.queryForObject("select count(*) from mailbox_sends where " + predicate, Long.class, arguments);
  }

  private static String metered() { return "outcome in ('" + String.join("', '", METERED) + "')"; }

  private static MailboxProblem capped(String detail) {
    return new MailboxProblem(HttpStatus.TOO_MANY_REQUESTS, "MAILBOX_CAP_REACHED", detail
        + " Send it from your own email app, or try again later.", true);
  }

  private static MailboxProblem notConnected() {
    return new MailboxProblem(HttpStatus.CONFLICT, "MAILBOX_NOT_CONNECTED", "Your email is not connected, so nothing was "
        + "sent. Send it from your own email app, or connect Gmail or Outlook on your account page.", true);
  }
}

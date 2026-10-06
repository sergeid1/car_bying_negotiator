package com.dealanalyzer.relay;

import com.dealanalyzer.buyingcase.EphemeralBuyingCaseService;
import com.dealanalyzer.buyingcase.ResourceNotFoundException;
import com.dealanalyzer.buyingcase.persistence.EphemeralCasePersistence;
import com.dealanalyzer.time.PersistedInstant;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one step every approved dealer message takes after its owner (quote request, counteroffer, acceptance or
 * test-drive message) re-checked it under the case lock (spec 2.1 section 2.1, ticket 82). The platform never
 * sends to a dealer itself (ticket 84). On the {@code OWN_EMAIL_APP} path the exact approved text is stored as
 * {@code HANDED_OFF}: no footer, no reply-intake address, no platform identity. The Buyer sends it from their own mailbox and then marks it sent, which
 * applies the owner's first-send state change once, through {@link RelayMessageOwner}. Nothing is sent or
 * retried automatically. Ticket 83 adds the connected-mailbox path behind this same step. Subjects, bodies and
 * addresses never enter logs or exception messages.
 */
@Service
public class DealerMessageHandoff {
  private static final Pattern ADDRESS = Pattern.compile("^[a-z0-9._%+\\-]{1,64}@[a-z0-9.\\-]{1,189}\\.[a-z]{2,24}$");
  private static final int MAX_SUBJECT = 300;
  private static final int MAX_BODY = 20_000;

  private final EphemeralBuyingCaseService cases;
  private final EphemeralCasePersistence locks;
  private final RelayService relay;
  private final RelayContentProtection content;
  private final ObjectProvider<RelayMessageOwner> owners;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  /** The only send path until ticket 83 adds {@code CONNECTED_MAILBOX} behind this same step. */
  private final SendPath path = SendPath.OWN_EMAIL_APP;

  DealerMessageHandoff(EphemeralBuyingCaseService cases, EphemeralCasePersistence locks, RelayService relay,
      RelayContentProtection content, ObjectProvider<RelayMessageOwner> owners, JdbcTemplate jdbc,
      PlatformTransactionManager transactions) {
    this.cases = cases; this.locks = locks; this.relay = relay; this.content = content; this.owners = owners;
    this.jdbc = jdbc; this.transaction = new TransactionTemplate(transactions);
  }

  /**
   * The stored message, and whether it already left ({@code sent}, for a path that sends it, such as ticket 83's
   * connected mailbox) or awaits the Buyer.
   */
  public record Outcome(String messageId, boolean sent) {
    /** The owner record's state: {@code SENT}, or {@code HANDED_OFF} until the Buyer marks it sent. */
    public String state() { return sent ? "SENT" : "HANDED_OFF"; }
  }

  /** An approved message for the Buyer to send from their own mail app. */
  public record Handoff(String messageId, SendPath sendPath, String state, String dealerAddress, String subject,
      String body, Instant handedOffAt, Instant sentAt) {
    @Override public String toString() { return "Handoff[redacted]"; }
  }

  /**
   * Hands off one approved message. The caller holds the case lock inside its transaction and has re-checked
   * the mandate, gate decision and approval of exactly this text. {@code released} values are the Buyer's
   * recorded release to this dealer; any other Buyer email, phone or surname is refused with
   * {@link RelayService.RelayRefusedException} ({@code BUYER_DATA}), as is an invalid address or message.
   */
  public Outcome handOff(String caseId, String dealerAddress, String subject, String body, Set<String> released) {
    String to = dealerAddress == null ? "" : dealerAddress.trim().toLowerCase(Locale.ROOT);
    if (to.length() > 254 || !ADDRESS.matcher(to).matches())
      throw new RelayService.RelayRefusedException(RelayService.Reason.INVALID, "A valid dealer email address is required.");
    if (subject == null || subject.isBlank() || subject.length() > MAX_SUBJECT || subject.matches("(?s).*[\\r\\n].*")
        || body == null || body.isBlank() || body.length() > MAX_BODY)
      throw RelayService.RelayRefusedException.of(RelayService.Reason.INVALID);
    String text = body.strip();
    if (relay.revealsBuyer(caseId, subject + "\n" + text, released))
      throw RelayService.RelayRefusedException.of(RelayService.Reason.BUYER_DATA);
    String messageId = UUID.randomUUID().toString();
    jdbc.update("""
        insert into dealer_relay_messages (id, case_id, identity_id, direction, trust, dealer_address, dealer_address_hash,
          state, subject_ciphertext, body_ciphertext, size_bytes, created_at, send_path)
        values (?, ?, null, 'OUTBOUND', 'PLATFORM', ?, ?, 'HANDED_OFF', ?, ?, ?, ?, ?)
        """, messageId, caseId, to, relay.dealerAddressHash(to), content.encrypt(caseId, messageId, subject),
        content.encrypt(caseId, messageId, text), (subject + text).getBytes(StandardCharsets.UTF_8).length,
        Timestamp.from(PersistedInstant.round(Instant.now())), path.name());
    return new Outcome(messageId, false);
  }

  /** The exact approved text and the dealer address, for the Buyer's mail app. */
  public Handoff get(String caseId, String token, String messageId) {
    return transaction.execute(status -> { authorize(caseId, token); return read(caseId, messageId); });
  }

  /**
   * The Buyer sent the message from their own mailbox. Re-runs the owner's send checks (mandate, gate decision,
   * negotiation and appointment state) under the case lock, records the message as {@code SENT} with the time,
   * and applies the owner's first-send state change once. A message already marked sent or discarded, or one
   * the current mandate no longer permits, changes nothing and is refused with the reason.
   */
  public Handoff markSent(String caseId, String token, String messageId) {
    Runnable after = transaction.execute(status -> {
      authorize(caseId, token);
      Handoff handoff = read(caseId, messageId);
      requireWaiting(handoff, "You already marked this message sent.");
      RelayMessageOwner owner = owner(caseId, messageId);
      try {
        owner.requireResendPermitted(caseId, token, messageId);
      } catch (IllegalArgumentException stopped) {
        throw new HandoffRefusedException(stopped.getMessage() + " It was not marked sent.");
      }
      Instant now = PersistedInstant.round(Instant.now());
      jdbc.update("update dealer_relay_messages set state = 'SENT', completed_at = ? where id = ? and state = 'HANDED_OFF'",
          Timestamp.from(now), messageId);
      audit(caseId, "DEALER_MESSAGE_MARKED_SENT", handoff, now);
      return owner.sent(caseId, token, messageId);
    });
    // Notifications and other follow-up work run only after the case transaction committed.
    if (after != null) after.run();
    return get(caseId, token, messageId);
  }

  /** The Buyer will not send this message. Its record can be prepared again; nothing reaches the dealer. */
  public Handoff discard(String caseId, String token, String messageId) {
    return transaction.execute(status -> {
      authorize(caseId, token);
      Handoff handoff = read(caseId, messageId);
      if ("DISCARDED".equals(handoff.state())) return handoff;
      requireWaiting(handoff, "This message was marked sent and cannot be discarded.");
      Instant now = PersistedInstant.round(Instant.now());
      owner(caseId, messageId).unsent(caseId, messageId, "DISCARDED");
      jdbc.update("update dealer_relay_messages set state = 'DISCARDED', completed_at = ? where id = ? and state = 'HANDED_OFF'",
          Timestamp.from(now), messageId);
      audit(caseId, "DEALER_MESSAGE_DISCARDED", handoff, now);
      return read(caseId, messageId);
    });
  }

  /**
   * A dealer reply the Buyer pasted or uploaded into the Dealer Conversation that began with this quote request.
   * It is recorded as an untrusted inbound message from the conversation's dealer address and goes through the
   * reply-intake pipeline: attachments and quoted figures become Document Artifacts for review.
   */
  public RelayService.InboundResult addReply(String caseId, String token, String quoteRequestId, String key, String subject,
      String body, List<DealerMailRelay.Attachment> attachments) {
    if (key == null || key.isBlank() || key.length() > 200) throw new IllegalArgumentException("Provide an idempotency key.");
    String dealerAddress = transaction.execute(status -> {
      authorize(caseId, token);
      var states = jdbc.query("select state, dealer_address from quote_requests where case_id = ? and id = ?",
          (r, n) -> new String[] {r.getString(1), r.getString(2)}, caseId, quoteRequestId);
      if (states.isEmpty()) throw new ResourceNotFoundException("Quote request not found");
      if (!"SENT".equals(states.getFirst()[0]))
        throw new IllegalArgumentException("Mark the quote request sent before adding the dealer's reply.");
      return states.getFirst()[1];
    });
    var result = relay.receivePasted(caseId, key, dealerAddress, subject, body, attachments);
    return switch (result.outcome()) {
      case REJECTED_INVALID -> throw new IllegalArgumentException("Paste the dealer's reply or add its attachments.");
      case REJECTED_TOO_LARGE -> throw new IllegalArgumentException("The reply is too large. Add at most 10 attachments of up to 25 MB in total.");
      default -> result;
    };
  }

  private void requireWaiting(Handoff handoff, String sentReason) {
    if ("SENT".equals(handoff.state())) throw new HandoffRefusedException(sentReason);
    if ("DISCARDED".equals(handoff.state())) throw new HandoffRefusedException("This message was discarded. Prepare a new one.");
    if (!"HANDED_OFF".equals(handoff.state())) throw new HandoffRefusedException("This message is not waiting to be sent.");
  }

  private Handoff read(String caseId, String messageId) {
    return jdbc.query("""
        select send_path, state, dealer_address, subject_ciphertext, body_ciphertext, created_at, completed_at
        from dealer_relay_messages where case_id = ? and id = ? and direction = 'OUTBOUND' and send_path <> 'RELAY'
        """, (r, n) -> new Handoff(messageId, SendPath.valueOf(r.getString(1)), r.getString(2), r.getString(3),
            content.decrypt(caseId, messageId, r.getString(4)), content.decrypt(caseId, messageId, r.getString(5)),
            r.getTimestamp(6).toInstant(), "SENT".equals(r.getString(2))
                ? Optional.ofNullable(r.getTimestamp(7)).map(Timestamp::toInstant).orElse(null) : null), caseId, messageId)
        .stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Dealer message not found"));
  }

  /** The single record the message was prepared for. */
  private RelayMessageOwner owner(String caseId, String messageId) {
    var matching = owners.orderedStream().filter(owner -> owner.owns(caseId, messageId)).toList();
    if (matching.size() != 1) throw new IllegalStateException("A dealer message must have exactly one owner");
    return matching.getFirst();
  }

  /** The audit names the message and the event only, never addresses or content. */
  private void audit(String caseId, String event, Handoff handoff, Instant now) {
    jdbc.update("""
        insert into audit_events (id, case_id, event_type, aggregate_type, aggregate_id, aggregate_version,
          idempotency_key, actor, metadata_json, created_at)
        values (?, ?, ?, 'DEALER_MESSAGE', ?, 1, ?, 'BUYER', ?, ?)
        """, UUID.randomUUID().toString(), caseId, event, handoff.messageId(), UUID.randomUUID().toString(),
        "{\"sendPath\":\"" + handoff.sendPath().name() + "\"}", Timestamp.from(now));
  }

  private void authorize(String caseId, String token) { locks.lock(caseId); cases.reopen(caseId, token); }

  /** A mark-sent or discard that changes nothing. The message names the reason only. */
  public static final class HandoffRefusedException extends RuntimeException {
    HandoffRefusedException(String message) { super(message); }
  }
}

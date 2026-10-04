package com.dealanalyzer.relay;

import com.dealanalyzer.account.AccountRelayAccess;
import com.dealanalyzer.buyingcase.CaseAccessService;
import com.dealanalyzer.buyingcase.EphemeralBuyingCaseService;
import com.dealanalyzer.buyingcase.ResourceNotFoundException;
import com.dealanalyzer.buyingcase.persistence.CaseRetentionService;
import com.dealanalyzer.buyingcase.persistence.EphemeralCasePersistence;
import com.dealanalyzer.document.DocumentIntakeService;
import com.dealanalyzer.document.InvalidDocumentException;
import com.dealanalyzer.document.MalwareScanner;
import com.dealanalyzer.notification.BuyerNotifications;
import com.dealanalyzer.notification.NotificationType;
import com.dealanalyzer.relay.DealerMailRelay.InboundMail;
import com.dealanalyzer.relay.DealerMailRelay.OutboundMail;
import com.dealanalyzer.relay.DealerMailRelay.RelayUnavailableException;
import com.dealanalyzer.time.PersistedInstant;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The dealer email channel for a Buying Case (AGT-03, AGT-04, AGT-11). It carries messages; deciding
 * what may be said is the outbound policy gate's job, wired in front of {@link #send} by later tickets.
 * Subjects, bodies and addresses never enter logs or exception messages.
 */
@Service
public class RelayService {
  /** Fixed footer on every outbound message. The opt-out link is appended per relay identity. */
  public static final String FOOTER = """

      --
      Deal X-Ray sent this message on behalf of a car Buyer it represents. It was prepared with AI assistance.
      Replies to this address reach the Buyer through Deal X-Ray.
      To stop all messages from this address, reply STOP or open:\s""";
  private static final Pattern ADDRESS = Pattern.compile("^[a-z0-9._%+\\-]{1,64}@[a-z0-9.\\-]{1,189}\\.[a-z]{2,24}$");
  private static final Pattern STOP = Pattern.compile("^(stop|unsubscribe|opt out|opt-out)[.!]?$", Pattern.CASE_INSENSITIVE);
  private static final int MAX_SUBJECT = 300;
  private static final int MAX_BODY = 20_000;
  private static final int MAX_ATTACHMENTS = 10;

  private final EphemeralBuyingCaseService cases;
  private final EphemeralCasePersistence locks;
  private final DealerMailRelay relay;
  private final RelayContentProtection content;
  private final AccountRelayAccess accounts;
  private final CaseAccessService secrets;
  private final CaseRetentionService retention;
  private final DocumentIntakeService intake;
  private final MalwareScanner scanner;
  private final BuyerNotifications notifications;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final SecureRandom random = new SecureRandom();
  private final int perCaseDaily;
  private final int perDealerDaily;
  private final long maxInboundBytes;
  private final String publicUrl;

  RelayService(EphemeralBuyingCaseService cases, EphemeralCasePersistence locks, DealerMailRelay relay,
      RelayContentProtection content, AccountRelayAccess accounts, CaseAccessService secrets,
      CaseRetentionService retention, DocumentIntakeService intake, MalwareScanner scanner,
      BuyerNotifications notifications, JdbcTemplate jdbc, PlatformTransactionManager transactions,
      @Value("${dealer-relay.cap.per-case-daily:10}") int perCaseDaily,
      @Value("${dealer-relay.cap.per-dealer-daily:20}") int perDealerDaily,
      @Value("${dealer-relay.inbound.max-bytes:26214400}") long maxInboundBytes,
      @Value("${dealer-relay.public-url:${case.recovery.public-url:http://localhost:5173/}}") String publicUrl) {
    this.cases = cases; this.locks = locks; this.relay = relay; this.content = content; this.accounts = accounts;
    this.secrets = secrets; this.retention = retention; this.intake = intake; this.scanner = scanner;
    this.notifications = notifications; this.jdbc = jdbc; this.transaction = new TransactionTemplate(transactions);
    this.perCaseDaily = perCaseDaily; this.perDealerDaily = perDealerDaily; this.maxInboundBytes = maxInboundBytes;
    this.publicUrl = publicUrl.endsWith("/") ? publicUrl : publicUrl + "/";
  }

  // ---------------------------------------------------------------- outbound

  /**
   * Sends one message to a dealer from the case's relay address and returns the stored message id.
   * Refuses (with {@link RelayRefusedException}) when the case cannot use the relay, the dealer opted out,
   * a cap is reached, or the rendered message contains the Buyer's email, phone or surname. Every attempt
   * is metered, including refusals.
   */
  public String send(String caseId, String dealerAddress, String subject, String body) {
    String to = normalizeAddress(dealerAddress);
    if (subject == null || subject.isBlank() || subject.length() > MAX_SUBJECT || subject.matches("(?s).*[\\r\\n].*"))
      throw new RelayRefusedException(Reason.INVALID, "A one-line subject of up to " + MAX_SUBJECT + " characters is required.");
    if (body == null || body.isBlank() || body.length() > MAX_BODY)
      throw new RelayRefusedException(Reason.INVALID, "A message body of up to " + MAX_BODY + " characters is required.");
    String dealerHash = dealerHash(to);
    Prepared prepared = transaction.execute(status -> {
      Instant now = PersistedInstant.round(Instant.now());
      if (!lockAccepting(caseId, now)) return new Prepared(Reason.UNAVAILABLE, null, null);
      Identity identity = ensureIdentity(caseId, now);
      if (identity.optedOutAt() != null) return refuse(caseId, dealerHash, Reason.OPTED_OUT, now);
      String rendered = body.strip() + FOOTER + optOutUrl(identity.id());
      if (!BuyerIdentityScreen.find(subject + "\n" + rendered, buyerIdentity(caseId)).isEmpty())
        return refuse(caseId, dealerHash, Reason.BUYER_DATA, now);
      Timestamp dayAgo = Timestamp.from(now.minus(Duration.ofDays(1)));
      if (count("case_id = ?", caseId, dayAgo) >= perCaseDaily) return refuse(caseId, dealerHash, Reason.CASE_CAP, now);
      if (count("dealer_address_hash = ?", dealerHash, dayAgo) >= perDealerDaily)
        return refuse(caseId, dealerHash, Reason.DEALER_CAP, now);
      String messageId = UUID.randomUUID().toString();
      jdbc.update("""
          insert into dealer_relay_messages (id, case_id, identity_id, direction, trust, dealer_address, dealer_address_hash,
            state, subject_ciphertext, body_ciphertext, size_bytes, created_at)
          values (?, ?, ?, 'OUTBOUND', 'PLATFORM', ?, ?, 'DELIVERY_UNKNOWN', ?, ?, ?, ?)
          """, messageId, caseId, identity.id(), to, dealerHash, content.encrypt(caseId, messageId, subject),
          content.encrypt(caseId, messageId, rendered),
          (subject + rendered).getBytes(StandardCharsets.UTF_8).length, Timestamp.from(now));
      meter(caseId, dealerHash, messageId, "ACCEPTED", now);
      return new Prepared(null, messageId,
          new OutboundMail(identity.address(), to, subject, rendered, optOutUrl(identity.id())));
    });
    if (prepared.refusal() != null) throw RelayRefusedException.of(prepared.refusal());
    String providerId;
    try {
      providerId = relay.send(prepared.mail());
    } catch (RelayUnavailableException unavailable) {
      complete(prepared.messageId(), "FAILED", null, true);
      throw RelayRefusedException.of(Reason.FAILED);
    } catch (RuntimeException uncertain) {
      // The provider may have accepted it; the message stays DELIVERY_UNKNOWN and must not be resent blindly.
      throw RelayRefusedException.of(Reason.DELIVERY_UNKNOWN);
    }
    complete(prepared.messageId(), "SENT", providerId, false);
    return prepared.messageId();
  }

  /** A provider delivery report for an outbound message. Unknown ids are ignored. */
  public void recordDelivery(String providerMessageId, boolean delivered) {
    jdbc.update("""
        update dealer_relay_messages set state = ?, completed_at = ?
        where direction = 'OUTBOUND' and provider_message_id = ? and state in ('SENT', 'DELIVERY_UNKNOWN')
        """, delivered ? "DELIVERED" : "BOUNCED", Timestamp.from(PersistedInstant.round(Instant.now())), providerMessageId);
  }

  private Prepared refuse(String caseId, String dealerHash, Reason reason, Instant now) {
    meter(caseId, dealerHash, null, reason.name(), now);
    return new Prepared(reason, null, null);
  }

  private void complete(String messageId, String state, String providerId, boolean failed) {
    transaction.executeWithoutResult(status -> {
      Timestamp now = Timestamp.from(PersistedInstant.round(Instant.now()));
      jdbc.update("update dealer_relay_messages set state = ?, provider_message_id = ?, completed_at = ? where id = ?",
          state, providerId, now, messageId);
      if (failed) jdbc.update("update dealer_relay_send_attempts set outcome = 'FAILED' where message_id = ?", messageId);
    });
  }

  private long count(String predicate, String value, Timestamp since) {
    return jdbc.queryForObject("select count(*) from dealer_relay_send_attempts where " + predicate
        + " and created_at > ? and outcome in ('ACCEPTED', 'FAILED')", Long.class, value, since);
  }

  private void meter(String caseId, String dealerHash, String messageId, String outcome, Instant now) {
    jdbc.update("insert into dealer_relay_send_attempts (id, case_id, dealer_address_hash, message_id, outcome, created_at) values (?, ?, ?, ?, ?, ?)",
        UUID.randomUUID().toString(), caseId, dealerHash, messageId, outcome, Timestamp.from(now));
  }

  private BuyerIdentityScreen.Identity buyerIdentity(String caseId) {
    var emails = new ArrayList<String>();
    var phones = new ArrayList<String>();
    var surnames = new ArrayList<String>();
    accounts.contact(caseId).ifPresent(contact -> {
      emails.add(contact.email()); phones.add(contact.phone()); surnames.add(contact.lastName());
    });
    emails.addAll(jdbc.queryForList("select email from case_recovery_contacts where case_id = ?", String.class, caseId));
    emails.addAll(jdbc.queryForList("select email from case_delivery_requests where case_id = ?", String.class, caseId));
    return new BuyerIdentityScreen.Identity(emails, phones, surnames);
  }

  // ---------------------------------------------------------------- inbound

  /**
   * Mail the provider received for a relay address. Mail for an unknown, deleted or expired case is
   * refused without being stored. Bodies are untrusted dealer content; attachments enter document intake.
   */
  public InboundResult receive(InboundMail mail) {
    if (mail == null || mail.providerMessageId() == null || mail.providerMessageId().isBlank()
        || mail.providerMessageId().length() > 200 || mail.to() == null)
      return new InboundResult(InboundOutcome.REJECTED_INVALID, null);
    long size = bytes(mail.subject()) + bytes(mail.body())
        + mail.attachments().stream().mapToLong(a -> a.content() == null ? 0 : a.content().length).sum();
    if (size > maxInboundBytes || mail.attachments().size() > MAX_ATTACHMENTS
        || (mail.body() != null && mail.body().length() > 1_000_000))
      return new InboundResult(InboundOutcome.REJECTED_TOO_LARGE, null);
    boolean clean = true;
    try {
      scanner.requireClean(((mail.subject() == null ? "" : mail.subject()) + "\n"
          + (mail.body() == null ? "" : mail.body())).getBytes(StandardCharsets.UTF_8));
    } catch (InvalidDocumentException malware) {
      clean = false;
    }
    boolean bodyClean = clean;
    String to = mail.to().trim().toLowerCase(Locale.ROOT);
    String from = mail.from() == null ? "" : mail.from().trim().toLowerCase(Locale.ROOT);
    Stored stored = transaction.execute(status -> {
      var identity = jdbc.query("select id, case_id from dealer_relay_identities where address = ?",
          (row, n) -> new String[] {row.getString(1), row.getString(2)}, to).stream().findFirst().orElse(null);
      if (identity == null) return null;
      Instant now = PersistedInstant.round(Instant.now());
      String caseId = identity[1];
      if (!lockAccepting(caseId, now)) return null;
      var duplicate = jdbc.queryForList("select id from dealer_relay_messages where direction = 'INBOUND' and provider_message_id = ?",
          String.class, mail.providerMessageId());
      if (!duplicate.isEmpty()) return new Stored(caseId, duplicate.getFirst(), true);
      String messageId = UUID.randomUUID().toString();
      jdbc.update("""
          insert into dealer_relay_messages (id, case_id, identity_id, direction, trust, dealer_address, dealer_address_hash,
            state, subject_ciphertext, body_ciphertext, size_bytes, provider_message_id, created_at, completed_at)
          values (?, ?, ?, 'INBOUND', 'UNTRUSTED', ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """, messageId, caseId, identity[0], from.length() > 254 ? from.substring(0, 254) : from, dealerHash(from),
          bodyClean ? "RECEIVED" : "REJECTED_MALWARE",
          bodyClean ? content.encrypt(caseId, messageId, mail.subject()) : null,
          bodyClean ? content.encrypt(caseId, messageId, mail.body()) : null,
          (int) Math.min(Integer.MAX_VALUE, size), mail.providerMessageId(), Timestamp.from(now), Timestamp.from(now));
      if (bodyClean && requestsOptOut(mail)) optOut(identity[0], "REPLY", now);
      return new Stored(caseId, messageId, false);
    });
    if (stored == null) return new InboundResult(InboundOutcome.REJECTED_UNKNOWN_ADDRESS, null);
    if (stored.duplicate()) return new InboundResult(InboundOutcome.DUPLICATE, stored.messageId());
    if (bodyClean) {
      for (int i = 0; i < mail.attachments().size(); i++) intakeAttachment(stored, i, mail.attachments().get(i));
    }
    transaction.execute(status -> accounts.accountId(stored.caseId()))
        .ifPresent(account -> notifications.notify(account, NotificationType.DEALER_REPLY, "relay-inbound:" + stored.messageId()));
    return new InboundResult(bodyClean ? InboundOutcome.RECEIVED : InboundOutcome.REJECTED_MALWARE, stored.messageId());
  }

  private void intakeAttachment(Stored stored, int ordinal, DealerMailRelay.Attachment attachment) {
    String documentId = null, outcome;
    try {
      if (attachment.content() == null || attachment.content().length == 0) throw new InvalidDocumentException("EMPTY_FILE", "Empty");
      documentId = intake.intakeRelayAttachment(stored.caseId(), "relay:" + stored.messageId() + ":" + ordinal,
          attachment.filename(), attachment.content()).document().id();
      outcome = "ACCEPTED";
    } catch (InvalidDocumentException rejected) {
      outcome = rejected.code();
    } catch (RuntimeException failed) {
      outcome = "INTAKE_FAILED";
    }
    String document = documentId, result = outcome.length() > 40 ? outcome.substring(0, 40) : outcome;
    transaction.executeWithoutResult(status -> {
      Instant now = PersistedInstant.round(Instant.now());
      if (!lockAccepting(stored.caseId(), now)) return;
      jdbc.update("insert into dealer_relay_attachments (id, message_id, case_id, ordinal, document_id, outcome, created_at) values (?, ?, ?, ?, ?, ?, ?)",
          UUID.randomUUID().toString(), stored.messageId(), stored.caseId(), ordinal, document, result, Timestamp.from(now));
    });
  }

  private static boolean requestsOptOut(InboundMail mail) {
    if (mail.subject() != null && STOP.matcher(mail.subject().strip()).matches()) return true;
    if (mail.body() == null) return false;
    return mail.body().lines().map(String::strip).filter(line -> !line.isEmpty()).findFirst()
        .map(line -> STOP.matcher(line).matches()).orElse(false);
  }

  // ---------------------------------------------------------------- dealer opt-out link

  /** A dealer's opt-out through the footer link. Returns false for an unknown link. */
  public boolean optOutByLink(String token) {
    if (token == null || token.isBlank() || token.length() > 128) return false;
    String hash = secrets.protectAccountSecret("relay-opt-out", token);
    Boolean found = transaction.execute(status -> {
      var identity = jdbc.queryForList("select id from dealer_relay_identities where opt_out_token_hash = ?", String.class, hash);
      if (identity.isEmpty()) return false;
      optOut(identity.getFirst(), "LINK", PersistedInstant.round(Instant.now()));
      return true;
    });
    return Boolean.TRUE.equals(found);
  }

  private void optOut(String identityId, String channel, Instant now) {
    jdbc.update("update dealer_relay_identities set dealer_opted_out_at = ?, opt_out_channel = ? where id = ? and dealer_opted_out_at is null",
        Timestamp.from(now), channel, identityId);
  }

  // ---------------------------------------------------------------- Buyer view

  /** The case's relay address, its status, and the messages sent and received through it. */
  public RelayView view(String caseId, String token) {
    cases.reopen(caseId, token);
    return transaction.execute(status -> {
      Instant now = PersistedInstant.round(Instant.now());
      if (!lockAccepting(caseId, now)) throw new ResourceNotFoundException("Buying Case not found");
      Identity identity = ensureIdentity(caseId, now);
      Timestamp dayAgo = Timestamp.from(now.minus(Duration.ofDays(1)));
      var attachments = jdbc.query("select message_id, ordinal, document_id, outcome from dealer_relay_attachments where case_id = ? order by message_id, ordinal",
          (row, n) -> Map.entry(row.getString(1), new AttachmentView(row.getInt(2), row.getString(3), row.getString(4))), caseId);
      var messages = jdbc.query("""
          select id, direction, trust, dealer_address, state, created_at, completed_at, size_bytes, body_ciphertext
          from dealer_relay_messages where case_id = ? order by created_at desc, id
          """, (row, n) -> {
            String id = row.getString(1);
            Instant created = row.getTimestamp(6).toInstant();
            return new MessageSummary(id, row.getString(2), row.getString(3), row.getString(4), row.getString(5), created,
                Optional.ofNullable(row.getTimestamp(7)).map(Timestamp::toInstant).orElse(null), row.getInt(8),
                row.getString(9) != null && retained(caseId, created, now),
                attachments.stream().filter(entry -> entry.getKey().equals(id)).map(Map.Entry::getValue).toList());
          }, caseId);
      return new RelayView(identity.address(), identity.optedOutAt() != null, identity.optedOutAt(), identity.channel(),
          perCaseDaily, count("case_id = ?", caseId, dayAgo), messages);
    });
  }

  /** One message with its content while retained. Inbound content is untrusted and shown as text only. */
  public MessageContent message(String caseId, String token, String messageId) {
    cases.reopen(caseId, token);
    return transaction.execute(status -> {
      Instant now = Instant.now();
      if (!lockAccepting(caseId, now)) throw new ResourceNotFoundException("Buying Case not found");
      var rows = jdbc.query("select direction, trust, subject_ciphertext, body_ciphertext, created_at from dealer_relay_messages where case_id = ? and id = ?",
          (row, n) -> new Object[] {row.getString(1), row.getString(2), row.getString(3), row.getString(4),
              row.getTimestamp(5).toInstant()}, caseId, messageId);
      if (rows.isEmpty()) throw new ResourceNotFoundException("Relay message not found");
      var row = rows.getFirst();
      boolean retained = retained(caseId, (Instant) row[4], now);
      return new MessageContent(messageId, (String) row[0], (String) row[1],
          retained ? content.decrypt(caseId, messageId, (String) row[2]) : null,
          retained ? content.decrypt(caseId, messageId, (String) row[3]) : null);
    });
  }

  /** Every message whose content is still retained, oldest first, for the case export. */
  public List<MessageContent> retainedContent(String caseId) {
    return transaction.execute(status -> {
      Instant now = Instant.now();
      return jdbc.query("""
          select id, direction, trust, subject_ciphertext, body_ciphertext, created_at from dealer_relay_messages
          where case_id = ? and body_ciphertext is not null order by created_at, id
          """, (row, n) -> new Object[] {row.getString(1), row.getString(2), row.getString(3), row.getString(4),
              row.getString(5), row.getTimestamp(6).toInstant()}, caseId).stream()
          .filter(row -> retained(caseId, (Instant) row[5], now))
          .map(row -> new MessageContent((String) row[0], (String) row[1], (String) row[2],
              content.decrypt(caseId, (String) row[0], (String) row[3]), content.decrypt(caseId, (String) row[0], (String) row[4])))
          .toList();
    });
  }

  // ---------------------------------------------------------------- retention

  /** Erases message subjects and bodies past the case's document retention deadline. */
  @Scheduled(fixedDelayString = "${dealer-relay.retention-sweep:PT5M}", initialDelayString = "${dealer-relay.retention-sweep:PT5M}")
  public void eraseExpiredContentNow() { eraseExpiredContent(Instant.now()); }

  public int eraseExpiredContent(Instant now) {
    var candidates = jdbc.query("""
        select id, case_id, created_at from dealer_relay_messages
        where body_erased_at is null and (subject_ciphertext is not null or body_ciphertext is not null)
        """, (row, n) -> new String[] {row.getString(1), row.getString(2), row.getTimestamp(3).toInstant().toString()});
    int erased = 0;
    for (String[] candidate : candidates) {
      try {
        if (retained(candidate[1], Instant.parse(candidate[2]), now)) continue;
      } catch (RuntimeException deleted) {
        continue;
      }
      erased += jdbc.update("update dealer_relay_messages set subject_ciphertext = null, body_ciphertext = null, body_erased_at = ? where id = ? and body_erased_at is null",
          Timestamp.from(PersistedInstant.round(now)), candidate[0]);
    }
    return erased;
  }

  private boolean retained(String caseId, Instant created, Instant now) {
    return retention.documentDeadline(caseId, created).isAfter(now);
  }

  // ---------------------------------------------------------------- identity

  /** Locks the case; true while it is active, unexpired, not being deleted, and its account is active. */
  private boolean lockAccepting(String caseId, Instant now) {
    try { locks.lock(caseId); } catch (IllegalArgumentException missing) { return false; }
    Long active = jdbc.queryForObject("""
        select count(*) from analysis_cases c where c.id = ? and c.lifecycle = 'ACTIVE' and c.access_expires_at > ?
          and not exists (select 1 from case_deletion_jobs j where j.case_id = c.id)
        """, Long.class, caseId, Timestamp.from(now));
    return active != null && active > 0 && accounts.accountActive(caseId);
  }

  private Identity ensureIdentity(String caseId, Instant now) {
    var existing = jdbc.query("select id, address, dealer_opted_out_at, opt_out_channel from dealer_relay_identities where case_id = ?",
        (row, n) -> new Identity(row.getString(1), row.getString(2),
            Optional.ofNullable(row.getTimestamp(3)).map(Timestamp::toInstant).orElse(null), row.getString(4)), caseId);
    if (!existing.isEmpty()) return existing.getFirst();
    String id = UUID.randomUUID().toString();
    String address = "c" + randomLocalPart() + "@" + relay.domain();
    jdbc.update("insert into dealer_relay_identities (id, case_id, address, opt_out_token_hash, created_at) values (?, ?, ?, ?, ?)",
        id, caseId, address, secrets.protectAccountSecret("relay-opt-out", optOutToken(id)), Timestamp.from(now));
    return new Identity(id, address, null, null);
  }

  /** 160 random bits, lower-case base32: unguessable and safe in every mail system. */
  private String randomLocalPart() {
    byte[] bytes = new byte[20]; random.nextBytes(bytes);
    String alphabet = "abcdefghijklmnopqrstuvwxyz234567";
    var out = new StringBuilder();
    int buffer = 0, bits = 0;
    for (byte b : bytes) {
      buffer = (buffer << 8) | (b & 0xff); bits += 8;
      while (bits >= 5) { out.append(alphabet.charAt((buffer >> (bits - 5)) & 31)); bits -= 5; }
    }
    return out.toString();
  }

  /** Derived, never stored: only its keyed hash is kept for lookup. */
  private String optOutToken(String identityId) { return secrets.protectAccountSecret("relay-opt-out-link", identityId); }

  private String optOutUrl(String identityId) { return publicUrl + "api/v1/relay/opt-out/" + optOutToken(identityId); }

  private String dealerHash(String address) { return secrets.protectAccountSecret("relay-dealer", address); }

  private static String normalizeAddress(String address) {
    String value = address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
    if (value.length() > 254 || !ADDRESS.matcher(value).matches())
      throw new RelayRefusedException(Reason.INVALID, "A valid dealer email address is required.");
    return value;
  }

  private static long bytes(String value) { return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length; }

  private record Identity(String id, String address, Instant optedOutAt, String channel) {}
  private record Prepared(Reason refusal, String messageId, OutboundMail mail) {}
  private record Stored(String caseId, String messageId, boolean duplicate) {}

  public enum Reason { INVALID, UNAVAILABLE, OPTED_OUT, BUYER_DATA, CASE_CAP, DEALER_CAP, FAILED, DELIVERY_UNKNOWN }
  public enum InboundOutcome { RECEIVED, DUPLICATE, REJECTED_MALWARE, REJECTED_TOO_LARGE, REJECTED_UNKNOWN_ADDRESS, REJECTED_INVALID }

  public record InboundResult(InboundOutcome outcome, String messageId) {}
  public record AttachmentView(int ordinal, String documentId, String outcome) {}
  public record MessageSummary(String id, String direction, String trust, String dealerAddress, String state,
      Instant createdAt, Instant completedAt, int sizeBytes, boolean contentAvailable, List<AttachmentView> attachments) {}
  public record MessageContent(String id, String direction, String trust, String subject, String body) {
    @Override public String toString() { return "MessageContent[redacted]"; }
  }
  public record RelayView(String address, boolean dealerOptedOut, Instant dealerOptedOutAt, String optOutChannel,
      int perCaseDailyCap, long sentLastDay, List<MessageSummary> messages) {}

  /** A refused send. The message names the reason only, never content or addresses. */
  public static final class RelayRefusedException extends RuntimeException {
    private final Reason reason;
    RelayRefusedException(Reason reason, String message) { super(message); this.reason = reason; }
    static RelayRefusedException of(Reason reason) {
      return new RelayRefusedException(reason, switch (reason) {
        case UNAVAILABLE -> "This Buying Case can no longer use the dealer email relay.";
        case OPTED_OUT -> "The dealer opted out of messages from this relay address.";
        case BUYER_DATA -> "The message contains the Buyer's contact details or surname and was not sent.";
        case CASE_CAP -> "This Buying Case reached its daily dealer message limit.";
        case DEALER_CAP -> "This dealer reached its daily message limit.";
        case FAILED -> "The relay did not accept the message. It was not sent.";
        case DELIVERY_UNKNOWN -> "The relay's response was unclear. Check delivery status before sending again.";
        case INVALID -> "The message is not valid.";
      });
    }
    public Reason reason() { return reason; }
  }
}

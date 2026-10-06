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
import com.dealanalyzer.time.PersistedInstant;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
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
 * Each Buying Case's private reply-intake address and its dealer messages (spec 2.1 section 2.2). The platform
 * never sends email to a dealer: approved messages leave through {@link DealerMessageHandoff} from the Buyer's
 * own mailbox (ticket 84). A Buyer who would rather forward a dealer's email than paste it forwards it to the
 * case's address; it is stored as untrusted dealer content and its attachments and figures enter document
 * intake. Subjects, bodies and addresses never enter logs or exception messages.
 */
@Service
public class RelayService {
  private static final Pattern ADDRESS = Pattern.compile("^[a-z0-9._%+\\-]{1,64}@[a-z0-9.\\-]{1,189}\\.[a-z]{2,24}$");
  private static final Pattern FORWARDED_FROM = Pattern.compile("(?im)^[>\\s]*from:[^\\r\\n]*$");
  private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+\\-]{1,64}@[A-Za-z0-9.\\-]{1,189}\\.[A-Za-z]{2,24}");
  private static final int MAX_SUBJECT = 300;
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
  private final com.dealanalyzer.quote.NegotiationStateService negotiation;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final SecureRandom random = new SecureRandom();
  private final long maxInboundBytes;

  RelayService(EphemeralBuyingCaseService cases, EphemeralCasePersistence locks, DealerMailRelay relay,
      RelayContentProtection content, AccountRelayAccess accounts, CaseAccessService secrets,
      CaseRetentionService retention, DocumentIntakeService intake, MalwareScanner scanner,
      BuyerNotifications notifications, com.dealanalyzer.quote.NegotiationStateService negotiation,
      JdbcTemplate jdbc, PlatformTransactionManager transactions,
      @Value("${dealer-relay.inbound.max-bytes:26214400}") long maxInboundBytes) {
    this.cases = cases; this.locks = locks; this.relay = relay; this.content = content; this.accounts = accounts;
    this.secrets = secrets; this.retention = retention; this.intake = intake; this.scanner = scanner;
    this.notifications = notifications; this.jdbc = jdbc; this.transaction = new TransactionTemplate(transactions);
    this.negotiation = negotiation; this.maxInboundBytes = maxInboundBytes;
  }

  // ---------------------------------------------------------------- Buyer identity screen

  /** True when the text carries the Buyer's email, phone or surname, other than the values released to this dealer. */
  boolean revealsBuyer(String caseId, String text, java.util.Set<String> released) {
    return !BuyerIdentityScreen.find(text, buyerIdentity(caseId, released)).isEmpty();
  }

  private BuyerIdentityScreen.Identity buyerIdentity(String caseId, java.util.Set<String> released) {
    var emails = new ArrayList<String>();
    var phones = new ArrayList<String>();
    var surnames = new ArrayList<String>();
    accounts.contact(caseId).ifPresent(contact -> {
      emails.add(contact.email()); phones.add(contact.phone()); surnames.add(contact.lastName());
    });
    emails.addAll(jdbc.queryForList("select email from case_recovery_contacts where case_id = ?", String.class, caseId));
    emails.addAll(jdbc.queryForList("select email from case_delivery_requests where case_id = ?", String.class, caseId));
    if (!released.isEmpty()) {
      var kept = (java.util.function.Predicate<String>) value -> value == null || !released.contains(value.strip());
      return new BuyerIdentityScreen.Identity(emails.stream().filter(kept).toList(), phones.stream().filter(kept).toList(),
          surnames.stream().filter(kept).toList());
    }
    return new BuyerIdentityScreen.Identity(emails, phones, surnames);
  }

  // ---------------------------------------------------------------- inbound

  /**
   * Mail the provider received for a case's reply-intake address, usually a dealer's email the Buyer forwarded.
   * Mail for an unknown, deleted or expired case is refused without being stored. The body, including any quoted
   * forward headers, is kept as untrusted dealer content; attachments and figures enter document intake. The
   * message joins the Dealer Conversation whose dealer address sent it or, for a forward, appears on exactly one
   * quoted {@code From:} line. Only an authenticated provider may call this (ticket 78; see
   * {@link RelayProductionGuard}).
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
      String dealer = conversationDealer(caseId, from, mail.body());
      jdbc.update("""
          insert into dealer_relay_messages (id, case_id, identity_id, direction, trust, dealer_address, dealer_address_hash,
            state, subject_ciphertext, body_ciphertext, size_bytes, provider_message_id, created_at, completed_at, send_path)
          values (?, ?, ?, 'INBOUND', 'UNTRUSTED', ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OWN_EMAIL_APP')
          """, messageId, caseId, identity[0], dealer.length() > 254 ? dealer.substring(0, 254) : dealer, dealerHash(dealer),
          bodyClean ? "RECEIVED" : "REJECTED_MALWARE",
          bodyClean ? content.encrypt(caseId, messageId, mail.subject()) : null,
          bodyClean ? content.encrypt(caseId, messageId, mail.body()) : null,
          (int) Math.min(Integer.MAX_VALUE, size), mail.providerMessageId(), Timestamp.from(now), Timestamp.from(now));
      return new Stored(caseId, messageId, false);
    });
    if (stored == null) return new InboundResult(InboundOutcome.REJECTED_UNKNOWN_ADDRESS, null);
    if (stored.duplicate()) return new InboundResult(InboundOutcome.DUPLICATE, stored.messageId());
    if (bodyClean) ingest(stored, mail.body(), mail.attachments());
    transaction.execute(status -> accounts.accountId(stored.caseId()))
        .ifPresent(account -> notifications.notify(account, NotificationType.DEALER_REPLY, "relay-inbound:" + stored.messageId()));
    return new InboundResult(bodyClean ? InboundOutcome.RECEIVED : InboundOutcome.REJECTED_MALWARE, stored.messageId());
  }

  /**
   * The Dealer Conversation address an inbound message belongs to: the sender when it is a conversation's dealer
   * (a dealer replying directly), otherwise the single conversation dealer named on a quoted {@code From:} line of
   * a forward. Anything else keeps the sender, so the message is listed but joins no conversation.
   */
  private String conversationDealer(String caseId, String from, String body) {
    var dealers = new java.util.HashSet<>(jdbc.queryForList(
        "select distinct lower(dealer_address) from quote_requests where case_id = ? and dealer_address is not null", String.class, caseId));
    if (dealers.contains(from) || body == null) return from;
    var named = new java.util.HashSet<String>();
    var lines = FORWARDED_FROM.matcher(body);
    while (lines.find()) {
      var emails = EMAIL.matcher(lines.group());
      while (emails.find()) {
        String email = emails.group().toLowerCase(Locale.ROOT);
        if (dealers.contains(email)) named.add(email);
      }
    }
    return named.size() == 1 ? named.iterator().next() : from;
  }

  /**
   * A dealer reply the Buyer pasted or uploaded into a Dealer Conversation (ticket 82). The caller has
   * authorized the case and chosen the conversation's dealer address. The reply is stored and ingested exactly
   * like relayed mail: it is untrusted, and attachments and quoted figures enter document intake. The key makes
   * a repeated submission return the first message.
   */
  public InboundResult receivePasted(String caseId, String key, String dealerAddress, String subject, String body,
      List<DealerMailRelay.Attachment> attachments) {
    var files = attachments == null ? List.<DealerMailRelay.Attachment>of() : attachments;
    if ((body == null || body.isBlank()) && files.isEmpty()) return new InboundResult(InboundOutcome.REJECTED_INVALID, null);
    long size = bytes(subject) + bytes(body) + files.stream().mapToLong(a -> a.content() == null ? 0 : a.content().length).sum();
    if (size > maxInboundBytes || files.size() > MAX_ATTACHMENTS || (body != null && body.length() > 1_000_000)
        || (subject != null && subject.length() > MAX_SUBJECT))
      return new InboundResult(InboundOutcome.REJECTED_TOO_LARGE, null);
    boolean clean = true;
    try {
      scanner.requireClean(((subject == null ? "" : subject) + "\n" + (body == null ? "" : body)).getBytes(StandardCharsets.UTF_8));
    } catch (InvalidDocumentException malware) {
      clean = false;
    }
    boolean bodyClean = clean;
    String to = normalizeAddress(dealerAddress);
    // Namespaced by case, so one Buyer's key can never match another case's reply.
    String pastedId = "pasted:" + secrets.protectAccountSecret("pasted-reply", caseId + ":" + key);
    Stored stored = transaction.execute(status -> {
      Instant now = PersistedInstant.round(Instant.now());
      if (!lockAccepting(caseId, now)) return null;
      var duplicate = jdbc.queryForList("select id from dealer_relay_messages where case_id = ? and direction = 'INBOUND' and provider_message_id = ?",
          String.class, caseId, pastedId);
      if (!duplicate.isEmpty()) return new Stored(caseId, duplicate.getFirst(), true);
      String messageId = UUID.randomUUID().toString();
      jdbc.update("""
          insert into dealer_relay_messages (id, case_id, identity_id, direction, trust, dealer_address, dealer_address_hash,
            state, subject_ciphertext, body_ciphertext, size_bytes, provider_message_id, created_at, completed_at, send_path)
          values (?, ?, null, 'INBOUND', 'UNTRUSTED', ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OWN_EMAIL_APP')
          """, messageId, caseId, to, dealerHash(to), bodyClean ? "RECEIVED" : "REJECTED_MALWARE",
          bodyClean ? content.encrypt(caseId, messageId, subject == null ? "" : subject) : null,
          bodyClean ? content.encrypt(caseId, messageId, body == null ? "" : body) : null,
          (int) Math.min(Integer.MAX_VALUE, size), pastedId, Timestamp.from(now), Timestamp.from(now));
      return new Stored(caseId, messageId, false);
    });
    if (stored == null) throw new ResourceNotFoundException("Buying Case not found");
    if (stored.duplicate()) return new InboundResult(InboundOutcome.DUPLICATE, stored.messageId());
    if (bodyClean) ingest(stored, body, files);
    return new InboundResult(bodyClean ? InboundOutcome.RECEIVED : InboundOutcome.REJECTED_MALWARE, stored.messageId());
  }

  /** Attachments and quoted figures of a clean inbound message enter document intake; the dealer has replied. */
  private void ingest(Stored stored, String body, List<DealerMailRelay.Attachment> attachments) {
    for (int i = 0; i < attachments.size(); i++) intakeAttachment(stored, i, attachments.get(i));
    // Capture bodies conservatively: figures need review even if the dealer did not label them a quote.
    if (body != null && !body.isBlank() && (attachments.isEmpty() || Pattern.compile("[0-9]").matcher(body).find()))
      intakeBody(stored, body);
    negotiation.dealerReply(stored.caseId());
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

  private void intakeBody(Stored stored, String body) {
    String documentId = null, outcome;
    try {
      documentId = intake.intakeRelayBody(stored.caseId(), stored.messageId(), body).document().id();
      outcome = "ACCEPTED";
    } catch (InvalidDocumentException rejected) { outcome = rejected.code(); }
    catch (RuntimeException failed) { outcome = "INTAKE_FAILED"; }
    String document = documentId, result = outcome;
    transaction.executeWithoutResult(status -> {
      if (!lockAccepting(stored.caseId(), Instant.now())) return;
      jdbc.update("insert into dealer_relay_attachments (id, message_id, case_id, ordinal, document_id, outcome, created_at) values (?, ?, ?, -1, ?, ?, ?)",
          UUID.randomUUID().toString(), stored.messageId(), stored.caseId(), document, result, Timestamp.from(Instant.now()));
    });
  }

  // ---------------------------------------------------------------- Buyer view

  /** The case's reply-intake address and every dealer message it has: handed off, sent, forwarded or pasted in. */
  public RelayView view(String caseId, String token) {
    cases.reopen(caseId, token);
    return transaction.execute(status -> {
      Instant now = PersistedInstant.round(Instant.now());
      if (!lockAccepting(caseId, now)) throw new ResourceNotFoundException("Buying Case not found");
      Identity identity = ensureIdentity(caseId, now);
      var attachments = jdbc.query("select message_id, ordinal, document_id, outcome from dealer_relay_attachments where case_id = ? and ordinal >= 0 order by message_id, ordinal",
          (row, n) -> Map.entry(row.getString(1), new AttachmentView(row.getInt(2), row.getString(3), row.getString(4))), caseId);
      var messages = jdbc.query("""
          select id, direction, trust, dealer_address, state, created_at, completed_at, size_bytes, body_ciphertext, send_path
          from dealer_relay_messages where case_id = ? order by created_at desc, id
          """, (row, n) -> {
            String id = row.getString(1);
            Instant created = row.getTimestamp(6).toInstant();
            boolean available = row.getString(9) != null && retained(caseId, created, now);
            return new MessageSummary(id, row.getString(2), row.getString(3), row.getString(4), row.getString(5), created,
                Optional.ofNullable(row.getTimestamp(7)).map(Timestamp::toInstant).orElse(null), row.getInt(8), available,
                attachments.stream().filter(entry -> entry.getKey().equals(id)).map(Map.Entry::getValue).toList(),
                SendPath.valueOf(row.getString(10)));
          }, caseId);
      return new RelayView(identity.address(), relay.receivesMail(), messages);
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
    try { locks.lock(caseId); } catch (ResourceNotFoundException missing) { return false; }
    Long active = jdbc.queryForObject("""
        select count(*) from analysis_cases c where c.id = ? and c.lifecycle = 'ACTIVE' and c.access_expires_at > ?
          and not exists (select 1 from case_deletion_jobs j where j.case_id = c.id)
        """, Long.class, caseId, Timestamp.from(now));
    return active != null && active > 0 && accounts.accountActive(caseId);
  }

  /** The case's reply-intake address, issued on first use. Addresses issued since ticket 84 have no opt-out link. */
  private Identity ensureIdentity(String caseId, Instant now) {
    var existing = jdbc.query("select id, address from dealer_relay_identities where case_id = ?",
        (row, n) -> new Identity(row.getString(1), row.getString(2)), caseId);
    if (!existing.isEmpty()) return existing.getFirst();
    String id = UUID.randomUUID().toString();
    String address = "c" + randomLocalPart() + "@" + relay.domain();
    jdbc.update("insert into dealer_relay_identities (id, case_id, address, created_at) values (?, ?, ?, ?)",
        id, caseId, address, Timestamp.from(now));
    return new Identity(id, address);
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

  String dealerAddressHash(String address) { return dealerHash(address); }

  private String dealerHash(String address) { return secrets.protectAccountSecret("relay-dealer", address); }

  private static String normalizeAddress(String address) {
    String value = address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
    if (value.length() > 254 || !ADDRESS.matcher(value).matches())
      throw new RelayRefusedException(Reason.INVALID, "A valid dealer email address is required.");
    return value;
  }

  private static long bytes(String value) { return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length; }

  private record Identity(String id, String address) {}
  private record Stored(String caseId, String messageId, boolean duplicate) {}

  public enum Reason { INVALID, BUYER_DATA }
  public enum InboundOutcome { RECEIVED, DUPLICATE, REJECTED_MALWARE, REJECTED_TOO_LARGE, REJECTED_UNKNOWN_ADDRESS, REJECTED_INVALID }

  public record InboundResult(InboundOutcome outcome, String messageId) {}
  public record AttachmentView(int ordinal, String documentId, String outcome) {}
  /**
   * {@code sendPath}: how an outbound message left, or how an inbound one arrived. {@code RELAY} marks messages the
   * platform relay sent or received before ticket 84; they stay listed with their recorded state.
   */
  public record MessageSummary(String id, String direction, String trust, String dealerAddress, String state,
      Instant createdAt, Instant completedAt, int sizeBytes, boolean contentAvailable, List<AttachmentView> attachments,
      SendPath sendPath) {}
  public record MessageContent(String id, String direction, String trust, String subject, String body) {
    @Override public String toString() { return "MessageContent[redacted]"; }
  }
  /**
   * {@code address}: the case's private reply-intake address, where the Buyer can forward a dealer's email.
   * {@code forwardingAvailable}: mail sent to it can arrive, because an authenticated inbound provider is enabled.
   */
  public record RelayView(String address, boolean forwardingAvailable, List<MessageSummary> messages) {}

  /** A message that cannot be handed off. The message names the reason only, never content or addresses. */
  public static final class RelayRefusedException extends RuntimeException {
    private final Reason reason;
    RelayRefusedException(Reason reason, String message) { super(message); this.reason = reason; }
    static RelayRefusedException of(Reason reason) {
      return new RelayRefusedException(reason, switch (reason) {
        case BUYER_DATA -> "The message contains the Buyer's contact details or surname and was not handed off.";
        case INVALID -> "The message is not valid.";
      });
    }
    public Reason reason() { return reason; }
  }
}

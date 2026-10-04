package com.dealanalyzer.appointment;

import com.dealanalyzer.account.AccountContactRelease;
import com.dealanalyzer.account.AccountContactRelease.Field;
import com.dealanalyzer.account.AccountRelayAccess;
import com.dealanalyzer.buyingcase.EphemeralBuyingCaseService;
import com.dealanalyzer.buyingcase.ResourceNotFoundException;
import com.dealanalyzer.buyingcase.persistence.EphemeralCasePersistence;
import com.dealanalyzer.notification.BuyerNotifications;
import com.dealanalyzer.notification.NotificationType;
import com.dealanalyzer.outbound.OutboundDraftService;
import com.dealanalyzer.outbound.OutboundGate;
import com.dealanalyzer.quote.NegotiationStateService;
import com.dealanalyzer.quote.WrittenQuoteEvidence;
import com.dealanalyzer.relay.RelayService;
import com.dealanalyzer.time.PersistedInstant;
import com.dealanalyzer.verification.persistence.DealerOfferPersistence;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Test-drive scheduling after an Agreed Price (spec 5.8, APPT-01, APPT-02). Every dealer message is
 * platform-rendered, gated, approved by the Buyer as exact text, and sent once through the relay. A
 * dealer reply only suggests a confirmation; the appointment exists once the Buyer confirms it and the
 * booking message is sent, carrying only the contact details the Buyer chose for this dealer.
 */
@Service
public class TestDriveService {
  private static final Pattern OPTION = Pattern.compile("(?i)\\boption\\s*(?:#|no\\.?|number)?\\s*([1-9])\\b");
  private static final TypeReference<List<TestDriveText.Slot>> SLOTS = new TypeReference<>() {};

  private final EphemeralBuyingCaseService cases;
  private final EphemeralCasePersistence locks;
  private final JdbcTemplate jdbc;
  private final OutboundDraftService drafts;
  private final NegotiationStateService negotiation;
  private final RelayService relay;
  private final AccountContactRelease contacts;
  private final AccountRelayAccess accounts;
  private final JourneyAppointment journeys;
  private final BuyerNotifications notifications;
  private final DealerOfferPersistence offers;
  private final ObjectMapper json;
  private final TransactionTemplate transaction;

  public TestDriveService(EphemeralBuyingCaseService cases, EphemeralCasePersistence locks, JdbcTemplate jdbc,
      OutboundDraftService drafts, NegotiationStateService negotiation, RelayService relay,
      AccountContactRelease contacts, AccountRelayAccess accounts, JourneyAppointment journeys,
      BuyerNotifications notifications, DealerOfferPersistence offers, ObjectMapper json,
      PlatformTransactionManager transactions) {
    this.cases = cases; this.locks = locks; this.jdbc = jdbc; this.drafts = drafts; this.negotiation = negotiation;
    this.relay = relay; this.contacts = contacts; this.accounts = accounts; this.journeys = journeys;
    this.notifications = notifications; this.offers = offers; this.json = json;
    this.transaction = new TransactionTemplate(transactions);
  }

  public record Availability(String appointmentId, String timeZone, List<TestDriveText.Window> windows) {}
  public record Booking(String proposalId, String messageId, Integer option, List<Field> release) {}
  public record Approval(boolean approved, String text) {}
  public record AgreedPriceReference(String id, int offerVersion, int baselineVersion, Long outTheDoorMinor) {}
  public record Message(String id, String kind, String appointmentId, String timeZone, List<TestDriveText.Slot> slots,
      List<Field> release, String state, String messageId, Instant createdAt, Instant sentAt,
      OutboundDraftService.StoredDraft gatedDraft) {}
  public record Release(List<Field> fields, String state, Instant decidedAt, Instant releasedAt) {}
  public record Appointment(String id, String agreedPriceId, String proposalId, int option, Instant startsAt, Instant endsAt,
      String timeZone, String label, String confirmationMessageId, String replacesAppointmentId, String state,
      Instant scheduledAt, Instant endedAt, Release release) {}
  public record Confirmation(String proposalId, String messageId, Instant receivedAt, String excerpt, Integer detectedOption) {}
  public record View(AgreedPriceReference agreedPrice, String dealerAddress, List<Field> contactFields,
      List<Message> messages, List<Appointment> appointments, List<Confirmation> confirmations) {}
  private record Agreed(String id, int offerVersion, int baselineVersion, String quoteRequestId, String dealerId, String dealerAddress) {}
  private record Sent(Message message, String notifyAccount, String notifyKey) {}

  // ---------------------------------------------------------------- reads

  @Transactional
  public View view(String caseId, String token) {
    authorize(caseId, token);
    Agreed agreed = agreed(caseId).orElse(null);
    if (agreed == null) return new View(null, null, List.of(), List.of(), List.of(), List.of());
    Long otd = WrittenQuoteEvidence.amount(offers.requireOffer(caseId, agreed.offerVersion()).terms(), "OUT_THE_DOOR_TOTAL");
    var fields = new ArrayList<>(contacts.available(caseId));
    return new View(new AgreedPriceReference(agreed.id(), agreed.offerVersion(), agreed.baselineVersion(), otd),
        agreed.dealerAddress(), fields, messages(caseId, token), appointments(caseId), confirmations(caseId, token, agreed));
  }

  /** The RFC 5545 calendar file for one appointment, referencing the Agreed Price. */
  @Transactional
  public String calendar(String caseId, String token, String appointmentId) {
    authorize(caseId, token);
    Agreed agreed = requireAgreed(caseId);
    Appointment appointment = appointment(caseId, appointmentId);
    boolean cancelled = List.of("CANCELLED", "RESCHEDULED").contains(appointment.state());
    if (!cancelled && !List.of("SCHEDULED", "RESCHEDULING").contains(appointment.state()))
      throw new IllegalArgumentException("This test drive has not been booked.");
    String dealer = jdbc.queryForList("select name from dealers where case_id = ? and id = ?", String.class, caseId, agreed.dealerId())
        .stream().findFirst().orElse(null);
    Long otd = WrittenQuoteEvidence.amount(offers.requireOffer(caseId, agreed.offerVersion()).terms(), "OUT_THE_DOOR_TOTAL");
    String description = "Test drive before the final paperwork. Agreed Price: verified offer " + agreed.offerVersion()
        + (otd == null ? "" : ", out-the-door total " + String.format(java.util.Locale.ROOT, "%d.%02d", otd / 100, otd % 100))
        + ", Approved Baseline version " + agreed.baselineVersion()
        + ". Bring the one-page Agreed Price summary and check that the paperwork matches it.";
    return TestDriveCalendar.render(new TestDriveCalendar.Event(appointment.id(),
        appointment.endedAt() != null ? appointment.endedAt() : appointment.scheduledAt(),
        appointment.startsAt(), appointment.endsAt(), cancelled, dealer, description));
  }

  // ---------------------------------------------------------------- prepare messages

  @Transactional
  public Message propose(String caseId, String token, String key, Availability request) {
    authorize(caseId, token);
    requireKey(key);
    if (request == null) throw new IllegalArgumentException("Enter your availability.");
    String fingerprint = offers.fingerprint(request);
    var previous = replay(caseId, token, key, fingerprint);
    if (previous.isPresent()) return previous.orElseThrow();
    Agreed agreed = requireAgreed(caseId);
    ZoneId zone;
    try { zone = ZoneId.of(request.timeZone() == null ? "" : request.timeZone()); }
    catch (DateTimeException invalid) { throw new IllegalArgumentException("Choose a valid time zone."); }
    var slots = TestDriveText.slots(request.windows(), zone, Instant.now());
    String kind, text;
    if (request.appointmentId() != null) {
      Appointment current = appointment(caseId, request.appointmentId());
      if (!"SCHEDULED".equals(current.state())) throw new IllegalArgumentException("Only a booked test drive can be moved.");
      kind = "RESCHEDULE"; text = TestDriveText.reschedule(current.label(), slots);
    } else {
      if (active(caseId).isPresent()) throw new IllegalArgumentException("A test drive is already booked. Move or cancel it instead.");
      kind = "PROPOSAL"; text = TestDriveText.proposal(slots);
    }
    return insert(caseId, token, key, fingerprint, agreed, kind, request.appointmentId(), zone.getId(), slots, text);
  }

  /** Records the Buyer's confirmation of a dealer reply and their contact choice, and prepares the booking message. */
  public Message book(String caseId, String token, String session, String key, Booking request) {
    return transaction.execute(status -> {
      String accountId = contacts.requireOwner(session, caseId);
      authorize(caseId, token);
      requireKey(key);
      if (request == null || request.proposalId() == null || request.messageId() == null || request.option() == null)
        throw new IllegalArgumentException("Choose the dealer reply and the time the dealer confirmed.");
      var fields = request.release() == null ? EnumSet.noneOf(Field.class)
          : request.release().isEmpty() ? EnumSet.noneOf(Field.class) : EnumSet.copyOf(request.release());
      String fingerprint = offers.fingerprint(request);
      var previous = replay(caseId, token, key, fingerprint);
      if (previous.isPresent()) return previous.orElseThrow();
      Agreed agreed = requireAgreed(caseId);
      Message proposal = message(caseId, token, request.proposalId());
      if (!List.of("PROPOSAL", "RESCHEDULE").contains(proposal.kind()) || !"SENT".equals(proposal.state())
          || !proposal.id().equals(latestSentProposal(caseId).orElse(null)))
        throw new IllegalArgumentException("Choose a reply to your latest sent test-drive times.");
      if (confirmations(caseId, token, agreed).stream().noneMatch(reply -> reply.messageId().equals(request.messageId())))
        throw new IllegalArgumentException("Choose a dealer reply received after your latest test-drive times.");
      var slot = proposal.slots().stream().filter(candidate -> candidate.option() == request.option()).findFirst()
          .orElseThrow(() -> new IllegalArgumentException("Choose one of the proposed times."));
      if (!slot.startsAt().isAfter(Instant.now())) throw new IllegalArgumentException("That time has passed. Propose new times.");
      String replaces = null;
      if ("RESCHEDULE".equals(proposal.kind())) {
        replaces = proposal.appointmentId();
        if (!"RESCHEDULING".equals(appointment(caseId, replaces).state()))
          throw new IllegalArgumentException("The test drive being moved has changed. Reload and try again.");
      } else if (active(caseId).isPresent()) throw new IllegalArgumentException("A test drive is already booked.");
      if (!fields.isEmpty()) contacts.values(accountId, caseId, fields);
      Instant now = PersistedInstant.round(Instant.now());
      // A newer choice replaces an unsent one.
      for (String pending : jdbc.queryForList("select id from test_drive_appointments where case_id = ? and state = 'PENDING_BOOKING'", String.class, caseId)) {
        jdbc.update("update test_drive_appointments set state = 'WITHDRAWN', ended_at = ? where id = ?", Timestamp.from(now), pending);
        jdbc.update("update test_drive_messages set state = 'WITHDRAWN' where appointment_id = ? and kind = 'BOOKING' and state = 'DRAFT'", pending);
      }
      String appointmentId = UUID.randomUUID().toString();
      jdbc.update("""
          insert into test_drive_appointments (id, case_id, agreed_price_id, proposal_id, slot_option, starts_at, ends_at, time_zone,
            confirmation_message_id, replaces_appointment_id, state, created_at)
          values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING_BOOKING', ?)
          """, appointmentId, caseId, agreed.id(), proposal.id(), slot.option(), Timestamp.from(slot.startsAt()),
          Timestamp.from(slot.endsAt()), proposal.timeZone(), request.messageId(), replaces, Timestamp.from(now));
      jdbc.update("""
          insert into test_drive_contact_releases (id, case_id, appointment_id, account_id, dealer_id, released_fields, state, decided_at)
          values (?, ?, ?, ?, ?, ?, 'PREPARED', ?)
          """, UUID.randomUUID().toString(), caseId, appointmentId, accountId, agreed.dealerId(), encode(fields), Timestamp.from(now));
      return insert(caseId, token, key, fingerprint, agreed, "BOOKING", appointmentId, proposal.timeZone(), List.of(slot),
          TestDriveText.booking(slot.label(), fields));
    });
  }

  @Transactional
  public Message cancel(String caseId, String token, String key, String appointmentId) {
    authorize(caseId, token);
    requireKey(key);
    String fingerprint = offers.fingerprint(Map.of("cancel", appointmentId));
    var previous = replay(caseId, token, key, fingerprint);
    if (previous.isPresent()) return previous.orElseThrow();
    Agreed agreed = requireAgreed(caseId);
    Appointment appointment = appointment(caseId, appointmentId);
    if (!List.of("SCHEDULED", "RESCHEDULING").contains(appointment.state()))
      throw new IllegalArgumentException("Only a booked test drive can be cancelled.");
    return insert(caseId, token, key, fingerprint, agreed, "CANCELLATION", appointmentId, appointment.timeZone(), List.of(),
        TestDriveText.cancellation(appointment.label()));
  }

  // ---------------------------------------------------------------- approve and send

  public Message approve(String caseId, String token, String session, String id, Approval approval) {
    Boolean reserved = transaction.execute(status -> {
      ownerIfBooking(caseId, token, session, id);
      authorize(caseId, token);
      Message message = message(caseId, token, id);
      if (approval == null || !approval.approved() || !message.gatedDraft().draft().text().equals(approval.text()))
        throw new IllegalArgumentException("Approve the exact displayed text before sending.");
      if (!"DRAFT".equals(message.state())) return false;
      requirePermitted(caseId, message);
      jdbc.update("update test_drive_messages set state = 'SENDING' where id = ?", id);
      return true;
    });
    if (!Boolean.TRUE.equals(reserved)) return transaction.execute(status -> { authorize(caseId, token); return message(caseId, token, id); });
    // The committed reservation prevents a second send after an uncertain provider handoff or a crash.
    Sent sent = transaction.execute(status -> {
      String accountId = ownerIfBooking(caseId, token, session, id);
      authorize(caseId, token);
      Message message = message(caseId, token, id);
      try { requirePermitted(caseId, message); }
      catch (IllegalArgumentException changed) {
        jdbc.update("update test_drive_messages set state = 'BLOCKED' where id = ?", id);
        return new Sent(message(caseId, token, id), null, null);
      }
      Agreed agreed = requireAgreed(caseId);
      String body = message.gatedDraft().draft().text();
      var released = new HashSet<String>();
      if ("BOOKING".equals(message.kind()) && !message.release().isEmpty()) {
        var values = contacts.values(accountId, caseId, message.release());
        released.addAll(values.values());
        body = body + "\n\n" + TestDriveText.releasedBlock(values);
      }
      String relayMessage;
      try {
        relayMessage = relay.send(caseId, agreed.dealerAddress(), TestDriveText.subject(message.kind()), body, released);
      } catch (RelayService.RelayRefusedException refused) {
        jdbc.update("update test_drive_messages set state = ? where id = ?", refused.reason().name(), id);
        return new Sent(message(caseId, token, id), null, null);
      }
      Instant now = PersistedInstant.round(Instant.now());
      jdbc.update("update test_drive_messages set state = 'SENT', message_id = ?, sent_at = ? where id = ?", relayMessage, Timestamp.from(now), id);
      String notifyKey = switch (message.kind()) {
        case "RESCHEDULE" -> {
          jdbc.update("update test_drive_appointments set state = 'RESCHEDULING' where id = ? and state = 'SCHEDULED'", message.appointmentId());
          yield null;
        }
        case "BOOKING" -> { booked(caseId, message, accountId, agreed, relayMessage, now); yield "appointment-booked:" + message.appointmentId(); }
        case "CANCELLATION" -> { cancelled(caseId, message, relayMessage, now); yield "appointment-cancelled:" + message.appointmentId(); }
        default -> null;
      };
      return new Sent(message(caseId, token, id), notifyKey == null ? null : accounts.accountId(caseId).orElse(null), notifyKey);
    });
    // Notifications run after the case transaction commits; they carry no appointment content.
    if (sent.notifyAccount() != null) notifications.notify(sent.notifyAccount(), NotificationType.APPOINTMENT, sent.notifyKey());
    return sent.message();
  }

  private void booked(String caseId, Message message, String accountId, Agreed agreed, String relayMessage, Instant now) {
    var appointment = appointment(caseId, message.appointmentId());
    jdbc.update("update test_drive_appointments set state = 'SCHEDULED', scheduled_at = ? where id = ?", Timestamp.from(now), appointment.id());
    if (appointment.replacesAppointmentId() != null)
      jdbc.update("update test_drive_appointments set state = 'RESCHEDULED', ended_at = ? where id = ? and state = 'RESCHEDULING'",
          Timestamp.from(now), appointment.replacesAppointmentId());
    boolean withheld = message.release().isEmpty();
    jdbc.update("update test_drive_contact_releases set state = ?, released_at = ? where appointment_id = ?",
        withheld ? "WITHHELD" : "RELEASED", withheld ? null : Timestamp.from(now), appointment.id());
    // The audit names the fields released and the dealer, never the values.
    audit(caseId, "CONTACT_DETAILS_RELEASED", "CONTACT_RELEASE", appointment.id(), Map.of("dealerId", agreed.dealerId(),
        "accountId", accountId, "fields", message.release().stream().map(Enum::name).toList(), "messageId", relayMessage), now);
    audit(caseId, "TEST_DRIVE_SCHEDULED", "TEST_DRIVE", appointment.id(), Map.of("agreedPriceId", agreed.id(),
        "confirmationMessageId", appointment.confirmationMessageId(), "startsAt", appointment.startsAt().toString()), now);
    journeys.scheduled(caseId);
  }

  private void cancelled(String caseId, Message message, String relayMessage, Instant now) {
    jdbc.update("update test_drive_appointments set state = 'CANCELLED', ended_at = ? where id = ? and state in ('SCHEDULED', 'RESCHEDULING')",
        Timestamp.from(now), message.appointmentId());
    audit(caseId, "TEST_DRIVE_CANCELLED", "TEST_DRIVE", message.appointmentId(), Map.of("messageId", relayMessage), now);
    if (active(caseId).isEmpty()) journeys.cancelled(caseId);
  }

  private void requirePermitted(String caseId, Message message) {
    var draft = message.gatedDraft();
    var context = drafts.context(caseId, draft.draft().dealerId());
    if (!draft.decision().pass() || !new OutboundGate().check(draft.draft(), context).pass()
        || context.mandate() == null || !context.mandate().id().equals(draft.mandateId()))
      throw new IllegalArgumentException("The current mandate or policy no longer permits this message. Prepare a new one.");
    if (negotiation.current(caseId) != NegotiationStateService.State.AGREED || agreed(caseId).isEmpty())
      throw new IllegalArgumentException("Accept a written quote before scheduling a test drive.");
    String state = message.appointmentId() == null ? null : appointment(caseId, message.appointmentId()).state();
    boolean current = switch (message.kind()) {
      case "PROPOSAL" -> active(caseId).isEmpty();
      case "RESCHEDULE" -> "SCHEDULED".equals(state);
      case "BOOKING" -> "PENDING_BOOKING".equals(state);
      default -> "SCHEDULED".equals(state) || "RESCHEDULING".equals(state);
    };
    if (!current) throw new IllegalArgumentException("The test drive changed. Prepare a new message.");
  }

  /** A booking releases contact details, so it requires the signed-in owner; returns the account for any kind. */
  private String ownerIfBooking(String caseId, String token, String session, String id) {
    var kind = jdbc.queryForList("select kind from test_drive_messages where case_id = ? and id = ?", String.class, caseId, id);
    if (kind.isEmpty()) throw new ResourceNotFoundException("Test-drive message not found");
    return "BOOKING".equals(kind.getFirst()) ? contacts.requireOwner(session, caseId) : null;
  }

  // ---------------------------------------------------------------- persistence helpers

  private Message insert(String caseId, String token, String key, String fingerprint, Agreed agreed, String kind,
      String appointmentId, String zone, List<TestDriveText.Slot> slots, String text) {
    String id = UUID.randomUUID().toString();
    // The row exists before the draft so the gate can ground exactly this rendered text.
    jdbc.update("""
        insert into test_drive_messages (id, case_id, agreed_price_id, quote_request_id, appointment_id, kind, time_zone, slots_json,
          message_text, state, idempotency_key, request_fingerprint, created_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?)
        """, id, caseId, agreed.id(), agreed.quoteRequestId(), appointmentId, kind, zone, write(slots), text, key, fingerprint,
        Timestamp.from(PersistedInstant.round(Instant.now())));
    var draft = drafts.create(caseId, token, "test-drive:" + offers.fingerprint(key), new OutboundGate.Draft(agreed.dealerId(), text));
    jdbc.update("update test_drive_messages set draft_id = ? where id = ?", draft.id(), id);
    return message(caseId, token, id);
  }

  private Optional<Message> replay(String caseId, String token, String key, String fingerprint) {
    var previous = jdbc.query("select id, request_fingerprint from test_drive_messages where case_id = ? and idempotency_key = ?",
        (r, n) -> new String[] {r.getString(1), r.getString(2)}, caseId, key);
    if (previous.isEmpty()) return Optional.empty();
    offers.requireMatchingFingerprint(previous.getFirst()[1], fingerprint);
    return Optional.of(message(caseId, token, previous.getFirst()[0]));
  }

  private List<Message> messages(String caseId, String token) {
    return jdbc.queryForList("select id from test_drive_messages where case_id = ? order by created_at, id", String.class, caseId)
        .stream().map(id -> message(caseId, token, id)).toList();
  }

  private Message message(String caseId, String token, String id) {
    return jdbc.query("""
        select t.kind, t.appointment_id, t.time_zone, t.slots_json, t.state, t.message_id, t.created_at, t.sent_at, t.draft_id,
          r.released_fields
        from test_drive_messages t left join test_drive_contact_releases r on r.appointment_id = t.appointment_id and t.kind = 'BOOKING'
        where t.case_id = ? and t.id = ?
        """, (r, n) -> new Message(id, r.getString(1), r.getString(2), r.getString(3), readSlots(r.getString(4)),
            decode(r.getString(10)), r.getString(5), r.getString(6), r.getTimestamp(7).toInstant(),
            Optional.ofNullable(r.getTimestamp(8)).map(Timestamp::toInstant).orElse(null),
            drafts.get(caseId, token, r.getString(9))), caseId, id)
        .stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Test-drive message not found"));
  }

  private List<Appointment> appointments(String caseId) {
    return jdbc.queryForList("select id from test_drive_appointments where case_id = ? order by created_at, id", String.class, caseId)
        .stream().map(id -> appointment(caseId, id)).toList();
  }

  private Appointment appointment(String caseId, String id) {
    return jdbc.query("""
        select a.agreed_price_id, a.proposal_id, a.slot_option, a.starts_at, a.ends_at, a.time_zone, a.confirmation_message_id,
          a.replaces_appointment_id, a.state, a.scheduled_at, a.ended_at, r.released_fields, r.state, r.decided_at, r.released_at
        from test_drive_appointments a left join test_drive_contact_releases r on r.appointment_id = a.id
        where a.case_id = ? and a.id = ?
        """, (r, n) -> {
          Instant start = r.getTimestamp(4).toInstant(), end = r.getTimestamp(5).toInstant();
          var release = r.getString(13) == null ? null : new Release(decode(r.getString(12)), r.getString(13),
              r.getTimestamp(14).toInstant(), Optional.ofNullable(r.getTimestamp(15)).map(Timestamp::toInstant).orElse(null));
          return new Appointment(id, r.getString(1), r.getString(2), r.getInt(3), start, end, r.getString(6),
              TestDriveText.label(start, end, ZoneId.of(r.getString(6))), r.getString(7), r.getString(8), r.getString(9),
              Optional.ofNullable(r.getTimestamp(10)).map(Timestamp::toInstant).orElse(null),
              Optional.ofNullable(r.getTimestamp(11)).map(Timestamp::toInstant).orElse(null), release);
        }, caseId, id).stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Test drive not found"));
  }

  private Optional<String> active(String caseId) {
    return jdbc.queryForList("select id from test_drive_appointments where case_id = ? and state in ('SCHEDULED', 'RESCHEDULING')",
        String.class, caseId).stream().findFirst();
  }

  private Optional<String> latestSentProposal(String caseId) {
    return jdbc.queryForList("""
        select id from test_drive_messages where case_id = ? and kind in ('PROPOSAL', 'RESCHEDULE') and state = 'SENT'
        order by sent_at desc, created_at desc, id desc
        """, String.class, caseId).stream().findFirst();
  }

  /**
   * Dealer replies to the latest sent times, with any option number they name. Dealer text is untrusted
   * content: it only suggests a confirmation for the Buyer to check, and never books anything itself.
   */
  private List<Confirmation> confirmations(String caseId, String token, Agreed agreed) {
    var latest = latestSentProposal(caseId);
    if (latest.isEmpty()) return List.of();
    Message proposal = message(caseId, token, latest.orElseThrow());
    boolean answered = jdbc.queryForObject("""
        select count(*) from test_drive_appointments where case_id = ? and proposal_id = ? and state in ('SCHEDULED', 'RESCHEDULING', 'RESCHEDULED', 'CANCELLED')
        """, Long.class, caseId, proposal.id()) > 0;
    if (answered) return List.of();
    Timestamp sent = jdbc.queryForObject("select created_at from dealer_relay_messages where id = ?", Timestamp.class, proposal.messageId());
    var replies = jdbc.query("""
        select id, created_at from dealer_relay_messages where case_id = ? and direction = 'INBOUND' and state = 'RECEIVED'
          and lower(dealer_address) = ? and created_at >= ? order by created_at, id
        """, (r, n) -> new Object[] {r.getString(1), r.getTimestamp(2).toInstant()}, caseId,
        agreed.dealerAddress().toLowerCase(java.util.Locale.ROOT), sent);
    var confirmations = new ArrayList<Confirmation>();
    for (Object[] reply : replies) {
      var content = relay.message(caseId, token, (String) reply[0]);
      String body = content.body() == null ? "" : content.body();
      var options = new HashSet<Integer>();
      var matcher = OPTION.matcher(body);
      while (matcher.find()) options.add(Integer.parseInt(matcher.group(1)));
      Integer detected = options.size() == 1 && options.iterator().next() <= proposal.slots().size() ? options.iterator().next() : null;
      String excerpt = body.strip();
      confirmations.add(new Confirmation(proposal.id(), (String) reply[0], (Instant) reply[1],
          excerpt.length() > 500 ? excerpt.substring(0, 500) + "…" : excerpt, detected));
    }
    return confirmations;
  }

  private Optional<Agreed> agreed(String caseId) {
    return jdbc.query("""
        select a.id, a.offer_version, a.baseline_version, r.quote_request_id, d.dealer_id, q.dealer_address
        from agreed_prices a join quote_acceptance_requests r on r.id = a.acceptance_request_id
        join quote_requests q on q.id = r.quote_request_id join outbound_drafts d on d.id = q.draft_id
        where a.case_id = ?
        """, (r, n) -> new Agreed(r.getString(1), r.getInt(2), r.getInt(3), r.getString(4), r.getString(5), r.getString(6)), caseId)
        .stream().findFirst();
  }

  private Agreed requireAgreed(String caseId) {
    return agreed(caseId).orElseThrow(() -> new IllegalArgumentException("Accept a written quote before scheduling a test drive."));
  }

  private void audit(String caseId, String type, String aggregate, String aggregateId, Map<String, Object> metadata, Instant now) {
    jdbc.update("""
        insert into audit_events (id, case_id, event_type, aggregate_type, aggregate_id, aggregate_version,
          idempotency_key, actor, metadata_json, created_at)
        values (?, ?, ?, ?, ?, 1, ?, 'BUYER', ?, ?)
        """, UUID.randomUUID().toString(), caseId, type, aggregate, aggregateId, aggregateId, write(metadata), Timestamp.from(now));
  }

  private void authorize(String caseId, String token) { locks.lock(caseId); cases.reopen(caseId, token); }

  private static void requireKey(String key) {
    if (key == null || key.isBlank() || key.length() > 200) throw new IllegalArgumentException("Provide an idempotency key.");
  }

  private static String encode(java.util.Collection<Field> fields) {
    return String.join(",", Arrays.stream(Field.values()).filter(fields::contains).map(Enum::name).toList());
  }

  private static List<Field> decode(String fields) {
    if (fields == null || fields.isBlank()) return List.of();
    return Arrays.stream(fields.split(",")).map(Field::valueOf).toList();
  }

  private List<TestDriveText.Slot> readSlots(String value) {
    try { return json.readValue(value, SLOTS); }
    catch (java.io.IOException failure) { throw new IllegalStateException("Unable to read test-drive times", failure); }
  }

  private String write(Object value) {
    try { return json.writeValueAsString(value); }
    catch (java.io.IOException failure) { throw new IllegalStateException("Unable to store test drive", failure); }
  }
}

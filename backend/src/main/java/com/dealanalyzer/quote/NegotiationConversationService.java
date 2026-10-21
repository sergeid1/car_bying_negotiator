package com.dealanalyzer.quote;

import com.dealanalyzer.buyingcase.EphemeralBuyingCaseService;
import com.dealanalyzer.buyingcase.ResourceNotFoundException;
import com.dealanalyzer.buyingcase.persistence.EphemeralCasePersistence;
import com.dealanalyzer.mandate.MandateService;
import com.dealanalyzer.negotiation.Counteroffer;
import com.dealanalyzer.outbound.OutboundDraftService;
import com.dealanalyzer.outbound.OutboundGate;
import com.dealanalyzer.quote.NegotiationStateService.State;
import com.dealanalyzer.relay.DealerMessageHandoff;
import com.dealanalyzer.relay.RelayMessageOwner;
import com.dealanalyzer.relay.RelayService;
import com.dealanalyzer.verification.persistence.DealerOfferPersistence;
import com.dealanalyzer.verification.persistence.DealerOfferPersistence.DealerOfferView;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Supervised negotiation (spec 5.7, AGT-06, AGT-08, NEG-03). Counteroffers are drafted from the latest
 * finalized report for the current verified quote and the active mandate, pass the outbound gate, and
 * leave only after the Buyer approves the exact current text. Every edit is a new gated revision. The
 * conversation view lists every draft, gate decision, dealer message, attachment and send state.
 */
@Service
public class NegotiationConversationService implements RelayMessageOwner {
  public static final String SUBJECT = "Revised itemized written offer";
  public static final String SUBJECT_ES = "Oferta revisada por escrito y detallada";
  public static String subject(String language) { return "es".equals(language) ? SUBJECT_ES : SUBJECT; }
  static final Set<State> ACTIVE = Set.of(State.MANDATE_ACTIVE, State.AWAITING_DEALER, State.AWAITING_BUYER);

  private final EphemeralBuyingCaseService cases;
  private final EphemeralCasePersistence locks;
  private final JdbcTemplate jdbc;
  private final OutboundDraftService drafts;
  private final DealerOfferPersistence offers;
  private final MandateService mandates;
  private final NegotiationStateService negotiation;
  private final RelayService relay;
  private final DealerMessageHandoff handoffs;
  private final QuoteRequestService quotes;
  private final AgreedPriceService acceptances;
  private final DealerDeclinedService dealerDeclined;
  private final ObjectMapper json;
  private final TransactionTemplate transaction;

  public NegotiationConversationService(EphemeralBuyingCaseService cases, EphemeralCasePersistence locks, JdbcTemplate jdbc,
      OutboundDraftService drafts, DealerOfferPersistence offers, MandateService mandates, NegotiationStateService negotiation,
      RelayService relay, QuoteRequestService quotes, AgreedPriceService acceptances, ObjectMapper json,
      PlatformTransactionManager transactions, DealerDeclinedService dealerDeclined, DealerMessageHandoff handoffs) {
    this.cases = cases; this.locks = locks; this.jdbc = jdbc; this.drafts = drafts; this.offers = offers;
    this.mandates = mandates; this.negotiation = negotiation; this.relay = relay; this.quotes = quotes;
    this.acceptances = acceptances; this.json = json; this.transaction = new TransactionTemplate(transactions);
    this.dealerDeclined = dealerDeclined; this.handoffs = handoffs;
  }

  public record Create(String quoteRequestId) {}
  public record Edit(String text) {}
  public record Approval(boolean approved, String text) {}
  /** {@code language} is the dealer's email language this revision is written in; an edit keeps it. */
  public record Revision(int revision, boolean editedByBuyer, String text, OutboundGate.Decision decision, Instant createdAt,
      String language) {}
  public record Draft(String id, String quoteRequestId, String dealerAddress, String state, String cancelReason,
      Long proposalMinor, int reportVersion, int offerVersion, List<Counteroffer.Paragraph> grounds,
      List<Revision> revisions, String messageId, Instant createdAt, Instant decidedAt) {
    public Revision current() { return revisions.getLast(); }
  }
  public record Attachment(int ordinal, String documentId, String outcome, Integer offerVersion) {}
  public record Message(String id, String direction, String dealerAddress, String deliveryState, Instant createdAt,
      Instant completedAt, boolean contentAvailable, String subject, String body, List<Attachment> attachments,
      com.dealanalyzer.relay.SendPath sendPath) {
    @Override public String toString() { return "Message[redacted]"; }
  }
  public enum Kind { QUOTE_REQUEST, ACCEPTANCE_REQUEST, COUNTEROFFER, OUTBOUND_MESSAGE, INBOUND_MESSAGE }
  /** One timeline entry; exactly one of the payloads is set, matching {@code kind}. */
  public record Entry(Kind kind, Instant at, QuoteRequestService.Quote quoteRequest, AgreedPriceService.Request acceptance,
      Draft counteroffer, Message message) {}
  public record MandateSummary(int version, String status, String currency, long targetMinor,
      long ceilingMinor, Instant expiresAt) {}
  public record Conversation(String quoteRequestId, String dealerAddress) {}
  public record View(State negotiationState, MandateSummary mandate, boolean targetReached, List<Conversation> conversations,
      List<Entry> entries, DealerDeclinedService.Decision dealerDeclined) {}

  // ---------------------------------------------------------------- drafting

  @Transactional
  public Draft create(String caseId, String token, String key, Create request) {
    authorize(caseId, token);
    if (request == null || request.quoteRequestId() == null || key == null || key.isBlank() || key.length() > 200)
      throw new IllegalArgumentException("Choose a dealer conversation and provide an idempotency key.");
    String fingerprint = offers.fingerprint(request);
    var previous = jdbc.query("select id, request_fingerprint from negotiation_drafts where case_id = ? and idempotency_key = ?",
        (r, n) -> new String[] {r.getString(1), r.getString(2)}, caseId, key);
    if (!previous.isEmpty()) {
      offers.requireMatchingFingerprint(previous.getFirst()[1], fingerprint);
      return get(caseId, token, previous.getFirst()[0]);
    }
    requireActive(caseId);
    requireDealerMoving(caseId);
    var mandate = activeMandate(caseId);
    String dealerId = sentDealer(caseId, request.quoteRequestId());
    var offer = currentOffer(caseId);
    Long selling = WrittenQuoteEvidence.amount(offer.terms(), "SELLING_PRICE");
    JsonNode report = latestReport(caseId);
    if (!offer.id().equals(report.at("/evidence/dealerOffer/id").asText()))
      throw new IllegalArgumentException("Run the analysis for the latest verified quote before drafting a counteroffer.");
    Counteroffer.Draft draft;
    String language = drafts.language(caseId, dealerId);
    try { draft = Counteroffer.draft(report, mandate.currency(), mandate.targetMinor(), mandate.ceilingMinor(), selling, language); }
    catch (Counteroffer.TargetReached reached) { throw new IllegalArgumentException(reached.getMessage()); }
    Instant now = com.dealanalyzer.time.PersistedInstant.round(Instant.now());
    // One queued draft at a time: a new draft replaces any older one awaiting approval.
    jdbc.update("update negotiation_drafts set state = 'CANCELLED', cancel_reason = 'SUPERSEDED', decided_at = ? where case_id = ? and state = 'PROPOSED'",
        Timestamp.from(now), caseId);
    var gated = drafts.create(caseId, token, "negotiation:" + offers.fingerprint(key), new OutboundGate.Draft(dealerId, draft.text()), language);
    String id = UUID.randomUUID().toString();
    jdbc.update("""
        insert into negotiation_drafts (id, case_id, quote_request_id, mandate_id, report_version, dealer_offer_id, proposal_minor,
          grounds_json, state, idempotency_key, request_fingerprint, created_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, 'PROPOSED', ?, ?, ?)
        """, id, caseId, request.quoteRequestId(), mandate.id(), draft.reportVersion(), offer.id(), draft.proposalMinor(),
        write(draft.paragraphs()), key, fingerprint, Timestamp.from(now));
    revision(caseId, id, 1, gated.id(), false, now);
    return get(caseId, token, id);
  }

  /** The Buyer's edit becomes a new revision with its own gate decision; only the latest one can be approved. */
  @Transactional
  public Draft edit(String caseId, String token, String id, String key, Edit edit) {
    authorize(caseId, token);
    if (edit == null || edit.text() == null || edit.text().isBlank() || key == null || key.isBlank() || key.length() > 200)
      throw new IllegalArgumentException("Enter the revised message text.");
    Draft draft = get(caseId, token, id);
    String outboundKey = "negotiation-edit:" + offers.fingerprint(List.of(id, key));
    var replayed = jdbc.queryForList("""
        select r.outbound_draft_id from negotiation_draft_revisions r join outbound_drafts d on d.id = r.outbound_draft_id
        where r.negotiation_draft_id = ? and d.idempotency_key = ?
        """, String.class, id, outboundKey);
    var dealer = dealerId(caseId, draft);
    if (!replayed.isEmpty()) {
      // A repeated edit with the same key must carry the same text, as for every other idempotent write.
      drafts.create(caseId, token, outboundKey, new OutboundGate.Draft(dealer, edit.text().strip()), draft.current().language());
      return draft;
    }
    if (!"PROPOSED".equals(draft.state())) throw new IllegalArgumentException("Only a draft awaiting your approval can be edited.");
    var gated = drafts.create(caseId, token, outboundKey, new OutboundGate.Draft(dealer, edit.text().strip()), draft.current().language());
    revision(caseId, id, draft.current().revision() + 1, gated.id(), true, com.dealanalyzer.time.PersistedInstant.round(Instant.now()));
    return get(caseId, token, id);
  }

  @Transactional
  public Draft decline(String caseId, String token, String id) {
    authorize(caseId, token);
    Draft draft = get(caseId, token, id);
    if ("DECLINED".equals(draft.state())) return draft;
    if (!"PROPOSED".equals(draft.state())) throw new IllegalArgumentException("Only a draft awaiting your approval can be declined.");
    jdbc.update("update negotiation_drafts set state = 'DECLINED', decided_at = ? where id = ? and state = 'PROPOSED'",
        Timestamp.from(com.dealanalyzer.time.PersistedInstant.round(Instant.now())), id);
    return get(caseId, token, id);
  }

  // ---------------------------------------------------------------- sending

  public Draft approveAndSend(String caseId, String token, String id, Approval approval) {
    var reserved = transaction.execute(status -> {
      authorize(caseId, token);
      Draft draft = get(caseId, token, id);
      if (approval == null || !approval.approved() || !draft.current().text().equals(approval.text()))
        throw new IllegalArgumentException("Approve the exact displayed counteroffer text before sending.");
      if (List.of("CANCELLED", "DECLINED").contains(draft.state()))
        throw new IllegalArgumentException("This draft was " + draft.state().toLowerCase(java.util.Locale.ROOT) + " and cannot be sent.");
      if (!"PROPOSED".equals(draft.state())) return false;
      requirePermitted(caseId, draft);
      jdbc.update("update negotiation_drafts set state = 'SENDING', decided_at = ? where id = ?",
          Timestamp.from(com.dealanalyzer.time.PersistedInstant.round(Instant.now())), id);
      return true;
    });
    if (!Boolean.TRUE.equals(reserved)) return transaction.execute(status -> { authorize(caseId, token); return get(caseId, token, id); });
    // The committed reservation prevents a second hand-off after a repeated approval or a crash.
    return transaction.execute(status -> {
      authorize(caseId, token);
      Draft draft = get(caseId, token, id);
      try { requirePermitted(caseId, draft); }
      catch (IllegalArgumentException stopped) {
        jdbc.update("update negotiation_drafts set state = 'BLOCKED' where id = ?", id);
        return get(caseId, token, id);
      }
      // The case lock is held through the hand-off, so revocation waits for it or blocks it.
      try {
        var handoff = handoffs.handOff(caseId, draft.dealerAddress(), subject(draft.current().language()), draft.current().text(), Set.of());
        jdbc.update("update negotiation_drafts set state = ?, message_id = ? where id = ?", handoff.state(), handoff.messageId(), id);
        if (handoff.sent()) negotiation.transition(caseId, State.AWAITING_DEALER);
      } catch (RelayService.RelayRefusedException refused) {
        // An invalid message, or one revealing the Buyer's details, is not handed off.
        jdbc.update("update negotiation_drafts set state = ? where id = ?", refused.reason().name(), id);
      }
      return get(caseId, token, id);
    });
  }

  private void requirePermitted(String caseId, Draft draft) {
    requireDealerMoving(caseId);
    State state = negotiation.current(caseId);
    if (state == State.PAUSED) throw new IllegalArgumentException("Negotiation is paused. Resume it before sending.");
    if (!ACTIVE.contains(state)) throw new IllegalArgumentException("Negotiation is not active. No message can be sent.");
    var revision = draftRevision(caseId, draft);
    var context = drafts.context(caseId, revision.draft().dealerId(), draft.current().language());
    if (!revision.decision().pass() || !new OutboundGate().check(revision.draft(), context).pass()
        || context.mandate() == null || !context.mandate().id().equals(revision.mandateId()))
      throw new IllegalArgumentException("The current mandate or policy no longer permits this draft. Prepare a new counteroffer.");
    var offer = currentOffer(caseId);
    if (!offer.id().equals(jdbc.queryForObject("select dealer_offer_id from negotiation_drafts where id = ?", String.class, draft.id())))
      throw new IllegalArgumentException("A newer verified quote exists. Prepare a new counteroffer.");
    if (Counteroffer.targetReached(WrittenQuoteEvidence.amount(offer.terms(), "SELLING_PRICE"), context.mandate().targetMinor()))
      throw new IllegalArgumentException("The verified quote is at or below your target. Negotiation stopped.");
  }

  // ---------------------------------------------------------------- marked sent or discarded (RelayMessageOwner)

  @Override public boolean owns(String caseId, String relayMessageId) {
    return jdbc.queryForObject("select count(*) from negotiation_drafts where case_id = ? and message_id = ?",
        Long.class, caseId, relayMessageId) > 0;
  }

  @Override public void requireResendPermitted(String caseId, String token, String relayMessageId) {
    var ids = unsettled(caseId, relayMessageId);
    if (ids.isEmpty()) throw new IllegalArgumentException("This counteroffer was already sent.");
    requirePermitted(caseId, get(caseId, token, ids.getFirst()));
  }

  @Override public Runnable sent(String caseId, String token, String relayMessageId) {
    if (jdbc.update("update negotiation_drafts set state = 'SENT' where case_id = ? and message_id = ? and state = 'HANDED_OFF'",
        caseId, relayMessageId) == 1 && ACTIVE.contains(negotiation.current(caseId)))
      negotiation.transition(caseId, State.AWAITING_DEALER);
    return null;
  }

  @Override public void unsent(String caseId, String relayMessageId, String state) {
    jdbc.update("update negotiation_drafts set state = ? where case_id = ? and message_id = ? and state = 'HANDED_OFF'",
        state, caseId, relayMessageId);
  }

  private List<String> unsettled(String caseId, String relayMessageId) {
    return jdbc.queryForList("select id from negotiation_drafts where case_id = ? and message_id = ? and state = 'HANDED_OFF'",
        String.class, caseId, relayMessageId);
  }

  // ---------------------------------------------------------------- Buyer controls

  @Transactional
  public View recordDealerDeclined(String caseId, String token) {
    authorize(caseId, token);
    requireActive(caseId);
    dealerDeclined.recordBuyer(caseId);
    return view(caseId, token);
  }

  @Transactional
  public View clearDealerDeclined(String caseId, String token) {
    authorize(caseId, token);
    dealerDeclined.clearBuyer(caseId);
    return view(caseId, token);
  }

  private void requireDealerMoving(String caseId) {
    if (dealerDeclined.current(caseId) != null)
      throw new IllegalArgumentException("The dealer has declined to lower the verified selling price. Counteroffers have stopped; you can still accept the written quote or stop negotiation.");
  }

  @Transactional
  public View stop(String caseId, String token) {
    authorize(caseId, token);
    State state = negotiation.current(caseId);
    if (state != State.PAUSED && !ACTIVE.contains(state)) throw new IllegalArgumentException("Negotiation is not active.");
    negotiation.transition(caseId, State.STOPPED);
    return view(caseId, token);
  }

  @Transactional
  public View resume(String caseId, String token) {
    authorize(caseId, token);
    if (negotiation.current(caseId) != State.PAUSED) throw new IllegalArgumentException("Only a paused negotiation can be resumed.");
    var last = jdbc.queryForList("select direction from dealer_relay_messages where case_id = ? order by created_at desc, id limit 1",
        String.class, caseId);
    negotiation.transition(caseId, !last.isEmpty() && "OUTBOUND".equals(last.getFirst()) ? State.AWAITING_DEALER : State.AWAITING_BUYER);
    return view(caseId, token);
  }

  // ---------------------------------------------------------------- view

  @Transactional
  public View view(String caseId, String token) {
    authorize(caseId, token);
    State state = negotiation.current(caseId);
    var mandate = mandates.activeMandate(caseId).orElse(null);
    var declined = dealerDeclined.current(caseId);
    boolean reached = false;
    if (mandate != null) {
      var offer = offers.latestOffer(caseId).filter(value -> "VERIFIED".equals(value.status())).orElse(null);
      reached = offer != null && Counteroffer.targetReached(WrittenQuoteEvidence.amount(offer.terms(), "SELLING_PRICE"), mandate.targetMinor());
      if (reached)
        jdbc.update("update negotiation_drafts set state = 'CANCELLED', cancel_reason = 'TARGET_REACHED', decided_at = ? where case_id = ? and state = 'PROPOSED'",
            Timestamp.from(com.dealanalyzer.time.PersistedInstant.round(Instant.now())), caseId);
    }
    var entries = new ArrayList<Entry>();
    var quoteView = quotes.view(caseId, token);
    quoteView.requests().forEach(quote -> entries.add(new Entry(Kind.QUOTE_REQUEST, quote.gatedDraft().createdAt(), quote, null, null, null)));
    acceptances.view(caseId, token).requests().forEach(request ->
        entries.add(new Entry(Kind.ACCEPTANCE_REQUEST, request.gatedDraft().createdAt(), null, request, null, null)));
    jdbc.queryForList("select id from negotiation_drafts where case_id = ? order by created_at, id", String.class, caseId)
        .forEach(id -> { Draft draft = get(caseId, token, id); entries.add(new Entry(Kind.COUNTEROFFER, draft.createdAt(), null, null, draft, null)); });
    messages(caseId, token).forEach(message -> entries.add(new Entry("OUTBOUND".equals(message.direction()) ? Kind.OUTBOUND_MESSAGE
        : Kind.INBOUND_MESSAGE, message.createdAt(), null, null, null, message)));
    entries.sort(Comparator.comparing(Entry::at).thenComparing(entry -> entry.kind().ordinal()));
    var conversations = quoteView.requests().stream().filter(quote -> "SENT".equals(quote.state()))
        .map(quote -> new Conversation(quote.id(), quote.dealerAddress())).toList();
    return new View(state, mandate == null ? null : new MandateSummary(mandate.version(), mandate.status().name(),
        mandate.currency(), mandate.targetMinor(), mandate.ceilingMinor(), mandate.expiresAt()),
        reached, conversations, List.copyOf(entries), declined);
  }

  private List<Message> messages(String caseId, String token) {
    var attachments = jdbc.query("""
        select a.message_id, a.ordinal, a.document_id, a.outcome, o.offer_version from dealer_relay_attachments a
        left join document_extractions e on e.document_id = a.document_id
        left join dealer_offers o on o.extraction_id = e.id
        where a.case_id = ? order by a.ordinal, a.created_at, a.id
        """, (r, n) -> java.util.Map.entry(r.getString(1), new Attachment(r.getInt(2), r.getString(3), r.getString(4),
            (Integer) r.getObject(5))), caseId);
    return relay.view(caseId, token).messages().stream().map(summary -> {
      RelayService.MessageContent content = summary.contentAvailable() ? relay.message(caseId, token, summary.id()) : null;
      return new Message(summary.id(), summary.direction(), summary.dealerAddress(), summary.state(), summary.createdAt(),
          summary.completedAt(), content != null, content == null ? null : content.subject(), content == null ? null : content.body(),
          attachments.stream().filter(entry -> entry.getKey().equals(summary.id())).map(java.util.Map.Entry::getValue).toList(),
          summary.sendPath());
    }).toList();
  }

  // ---------------------------------------------------------------- reads

  private record StoredRevision(OutboundGate.Draft draft, String mandateId, OutboundGate.Decision decision) {}

  private StoredRevision draftRevision(String caseId, Draft draft) {
    String outbound = jdbc.queryForObject("""
        select outbound_draft_id from negotiation_draft_revisions where negotiation_draft_id = ? order by revision desc limit 1
        """, String.class, draft.id());
    var stored = jdbc.query(
        "select dealer_id, draft_text, mandate_id from outbound_drafts where id = ? and case_id = ?",
        (r, n) -> new String[] {r.getString(1), r.getString(2), r.getString(3)}, outbound, caseId).getFirst();
    return new StoredRevision(new OutboundGate.Draft(stored[0], stored[1]), stored[2], draft.current().decision());
  }

  private Draft get(String caseId, String token, String id) {
    var revisions = jdbc.query("""
        select revision, edited_by_buyer, outbound_draft_id, created_at from negotiation_draft_revisions
        where case_id = ? and negotiation_draft_id = ? order by revision
        """, (r, n) -> new Object[] {r.getInt(1), r.getBoolean(2), r.getString(3), r.getTimestamp(4).toInstant()}, caseId, id)
        .stream().map(row -> {
          var stored = drafts.get(caseId, token, (String) row[2]);
          return new Revision((Integer) row[0], (Boolean) row[1], stored.draft().text(), stored.decision(), (Instant) row[3],
              stored.language());
        }).toList();
    return jdbc.query("""
        select n.quote_request_id, q.dealer_address, n.state, n.cancel_reason, n.proposal_minor, n.report_version, o.offer_version,
          n.grounds_json, n.message_id, n.created_at, n.decided_at
        from negotiation_drafts n join quote_requests q on q.id = n.quote_request_id join dealer_offers o on o.id = n.dealer_offer_id
        where n.case_id = ? and n.id = ?
        """, (r, n) -> new Draft(id, r.getString(1), r.getString(2), r.getString(3), r.getString(4), (Long) r.getObject(5),
            r.getInt(6), r.getInt(7), read(r.getString(8)), revisions, r.getString(9), r.getTimestamp(10).toInstant(),
            r.getTimestamp(11) == null ? null : r.getTimestamp(11).toInstant()), caseId, id)
        .stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Counteroffer draft not found"));
  }

  private void revision(String caseId, String draftId, int number, String outboundDraftId, boolean edited, Instant now) {
    jdbc.update("""
        insert into negotiation_draft_revisions (id, case_id, negotiation_draft_id, revision, outbound_draft_id, edited_by_buyer, created_at)
        values (?, ?, ?, ?, ?, ?, ?)
        """, UUID.randomUUID().toString(), caseId, draftId, number, outboundDraftId, edited, Timestamp.from(now));
  }

  private void requireActive(String caseId) {
    State state = negotiation.current(caseId);
    if (state == State.PAUSED) throw new IllegalArgumentException("Negotiation is paused. Resume it before drafting.");
    if (!ACTIVE.contains(state)) throw new IllegalArgumentException("Negotiation is not active. Approve a current mandate first.");
  }

  private MandateService.Mandate activeMandate(String caseId) {
    return mandates.activeMandate(caseId)
        .orElseThrow(() -> new IllegalArgumentException("Approve a current Negotiation Mandate before drafting."));
  }

  private DealerOfferView currentOffer(String caseId) {
    return offers.latestOffer(caseId).filter(offer -> "VERIFIED".equals(offer.status()))
        .orElseThrow(() -> new IllegalArgumentException("Verify the dealer's quote before drafting a counteroffer."));
  }

  private JsonNode latestReport(String caseId) {
    var payload = jdbc.queryForList("select payload_json from analysis_reports where case_id = ? order by report_version desc limit 1",
        String.class, caseId);
    if (payload.isEmpty()) throw new IllegalArgumentException("Run the analysis for the latest verified quote before drafting a counteroffer.");
    try { return json.readTree(payload.getFirst()); }
    catch (java.io.IOException failure) { throw new IllegalStateException("Unable to read the report", failure); }
  }

  private String sentDealer(String caseId, String quoteRequestId) {
    return jdbc.query("""
        select d.dealer_id from quote_requests q join outbound_drafts d on d.id = q.draft_id
        where q.case_id = ? and q.id = ? and q.state = 'SENT'
        """, (r, n) -> r.getString(1), caseId, quoteRequestId).stream().findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Choose a dealer conversation with a sent quote request."));
  }

  private String dealerId(String caseId, Draft draft) { return sentDealer(caseId, draft.quoteRequestId()); }

  private void authorize(String caseId, String token) { locks.lock(caseId); cases.reopen(caseId, token); }

  private String write(Object value) {
    try { return json.writeValueAsString(value); }
    catch (java.io.IOException failure) { throw new IllegalStateException("Unable to store counteroffer grounds", failure); }
  }

  private List<Counteroffer.Paragraph> read(String value) {
    try { return json.readValue(value, new TypeReference<List<Counteroffer.Paragraph>>() {}); }
    catch (java.io.IOException failure) { throw new IllegalStateException("Unable to read counteroffer grounds", failure); }
  }
}

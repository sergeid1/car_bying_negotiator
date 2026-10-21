package com.dealanalyzer.quote;

import com.dealanalyzer.buyingcase.EphemeralBuyingCaseService;
import com.dealanalyzer.buyingcase.ResourceNotFoundException;
import com.dealanalyzer.buyingcase.persistence.EphemeralCasePersistence;
import com.dealanalyzer.outbound.OutboundDraftService;
import com.dealanalyzer.outbound.OutboundGate;
import com.dealanalyzer.relay.DealerMessageHandoff;
import com.dealanalyzer.relay.RelayMessageOwner;
import com.dealanalyzer.relay.RelayService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class QuoteRequestService implements RelayMessageOwner {
  public static final String SUBJECT = "Request for an itemized vehicle quote";
  public static final String SUBJECT_ES = "Solicitud de una cotización detallada del vehículo";
  public static String subject(String language) { return "es".equals(language) ? SUBJECT_ES : SUBJECT; }
  private final EphemeralBuyingCaseService cases;
  private final EphemeralCasePersistence locks;
  private final OutboundDraftService drafts;
  private final DealerMessageHandoff handoffs;
  private final NegotiationStateService negotiation;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  public QuoteRequestService(EphemeralBuyingCaseService cases, EphemeralCasePersistence locks,
      OutboundDraftService drafts, DealerMessageHandoff handoffs, NegotiationStateService negotiation, JdbcTemplate jdbc,
      PlatformTransactionManager transactions) {
    this.cases = cases; this.locks = locks; this.drafts = drafts; this.handoffs = handoffs; this.negotiation = negotiation; this.jdbc = jdbc;
    this.transaction = new TransactionTemplate(transactions);
  }
  public record Create(String dealerId, String dealerAddress) {}
  public record Approval(boolean approved, String text) {}
  public record Quote(String id, String dealerAddress, String state, String messageId,
      Instant approvedAt, OutboundDraftService.StoredDraft gatedDraft) {}
  public record ReplyDocument(String documentId, String outcome, Integer offerVersion, String sourceKind, String messageId) {}
  /** {@code emailLanguage} is the language the Buyer chose for this dealer's emails. */
  public record Dealer(String id, String name, String emailLanguage) {}
  public record LanguageChoice(String language) {}
  public record View(NegotiationStateService.State negotiationState, List<Quote> requests,
      List<ReplyDocument> documents, List<Dealer> dealers) {}

  @Transactional
  public Quote create(String caseId, String token, String key, Create request) {
    locks.lock(caseId); cases.reopen(caseId, token);
    if (request == null || request.dealerId() == null || request.dealerAddress() == null
        || !request.dealerAddress().matches("[a-zA-Z0-9._%+\\-]{1,64}@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,24}")
        || request.dealerAddress().length() > 254)
      throw new IllegalArgumentException("Choose a dealer and provide its email address.");
    var context = drafts.context(caseId, request.dealerId());
    String text = context.quoteRequestQuestion();
    if (text == null) throw new IllegalArgumentException("Confirm the VIN and listing before requesting a quote.");
    var draft = drafts.create(caseId, token, key, new OutboundGate.Draft(request.dealerId(), text));
    var existing = jdbc.queryForList("select id from quote_requests where draft_id = ?", String.class, draft.id());
    if (!existing.isEmpty()) {
      Quote previous = get(caseId, token, existing.getFirst());
      if (!previous.dealerAddress().equals(request.dealerAddress().toLowerCase(java.util.Locale.ROOT)))
        throw new com.dealanalyzer.buyingcase.IdempotencyConflictException();
      return previous;
    }
    String id = UUID.randomUUID().toString();
    jdbc.update("insert into quote_requests(id, case_id, draft_id, dealer_address, state, created_at) values (?, ?, ?, ?, 'DRAFT', ?)",
        id, caseId, draft.id(), request.dealerAddress().toLowerCase(java.util.Locale.ROOT), Timestamp.from(Instant.now()));
    negotiation.current(caseId);
    return get(caseId, token, id);
  }

  public Quote approveAndSend(String caseId, String token, String id, Approval approval) {
    var reservation = transaction.execute(status -> {
      locks.lock(caseId); cases.reopen(caseId, token);
      Quote quote = get(caseId, token, id);
      if (approval == null || !approval.approved() || !quote.gatedDraft().draft().text().equals(approval.text()))
        throw new IllegalArgumentException("Approve the exact displayed quote request text before sending.");
      if (!quote.state().equals("DRAFT")) return new Reservation(quote, false);
      requirePermitted(caseId, quote);
      jdbc.update("update quote_requests set state = 'SENDING', approved_at = ? where id = ?", Timestamp.from(Instant.now()), id);
      return new Reservation(quote, true);
    });
    if (!reservation.reserved()) return reservation.quote();
    // Commit the reservation before the hand-off, so a repeated approval cannot hand the request off twice.
    return transaction.execute(status -> {
      locks.lock(caseId); cases.reopen(caseId, token);
      Quote quote = get(caseId, token, id);
      try { requirePermitted(caseId, quote); }
      catch (IllegalArgumentException stopped) {
        jdbc.update("update quote_requests set state = 'BLOCKED' where id = ?", id);
        return get(caseId, token, id);
      }
      // Keep the case lock through the hand-off, serializing mandate revocation and deletion.
      try {
        var handoff = handoffs.handOff(caseId, quote.dealerAddress(), subject(quote.gatedDraft().language()),
            quote.gatedDraft().draft().text(), java.util.Set.of());
        jdbc.update("update quote_requests set state = ?, message_id = ? where id = ?", handoff.state(), handoff.messageId(), id);
        if (handoff.sent()) negotiation.transition(caseId, NegotiationStateService.State.AWAITING_DEALER);
      } catch (RelayService.RelayRefusedException refused) {
        // An invalid message, or one revealing the Buyer's details, is not handed off.
        jdbc.update("update quote_requests set state = ? where id = ?", refused.reason().name(), id);
      }
      return get(caseId, token, id);
    });
  }

  /**
   * Sets the language of one dealer's emails (ticket 149). Requests still awaiting approval are prepared again in
   * the new language and replace the old drafts, which stay in the case record; anything approved stays as it was.
   */
  @Transactional
  public View changeLanguage(String caseId, String token, String dealerId, LanguageChoice choice) {
    locks.lock(caseId); cases.reopen(caseId, token);
    if (choice == null || choice.language() == null) throw new IllegalArgumentException("Choose English or Spanish.");
    drafts.changeLanguage(caseId, dealerId, choice.language());
    var pending = jdbc.query("""
        select q.id, q.dealer_address from quote_requests q join outbound_drafts d on d.id = q.draft_id
        where q.case_id = ? and d.dealer_id = ? and q.state = 'DRAFT' and d.language <> ? order by q.created_at, q.id
        """, (r, n) -> new String[] {r.getString(1), r.getString(2)}, caseId, dealerId, choice.language());
    String text = drafts.context(caseId, dealerId).quoteRequestQuestion();
    for (String[] request : pending) {
      if (text == null) break;
      var draft = drafts.create(caseId, token, "quote-request-language:" + UUID.randomUUID(), new OutboundGate.Draft(dealerId, text));
      jdbc.update("update quote_requests set state = 'REPLACED' where id = ? and state = 'DRAFT'", request[0]);
      jdbc.update("insert into quote_requests(id, case_id, draft_id, dealer_address, state, created_at) values (?, ?, ?, ?, 'DRAFT', ?)",
          UUID.randomUUID().toString(), caseId, draft.id(), request[1], Timestamp.from(Instant.now()));
    }
    return view(caseId, token);
  }

  private void requirePermitted(String caseId, Quote quote) {
    var context = drafts.context(caseId, quote.gatedDraft().draft().dealerId(), quote.gatedDraft().language());
    var decision = new OutboundGate().check(quote.gatedDraft().draft(), context);
    if (!quote.gatedDraft().decision().pass() || !decision.pass() || context.mandate() == null
        || !context.mandate().id().equals(quote.gatedDraft().mandateId()))
      throw new IllegalArgumentException("The current mandate or policy no longer permits this draft. Prepare a new request.");
    var state = negotiation.current(caseId);
    if (state == NegotiationStateService.State.STOPPED || state == NegotiationStateService.State.AGREED)
      throw new IllegalArgumentException("Negotiation has stopped.");
    if (state == NegotiationStateService.State.PAUSED)
      throw new IllegalArgumentException("Negotiation is paused.");
  }
  private record Reservation(Quote quote, boolean reserved) {}

  // ---------------------------------------------------------------- marked sent or discarded (RelayMessageOwner)

  @Override public boolean owns(String caseId, String relayMessageId) {
    return jdbc.queryForObject("select count(*) from quote_requests where case_id = ? and message_id = ?",
        Long.class, caseId, relayMessageId) > 0;
  }

  @Override public void requireResendPermitted(String caseId, String token, String relayMessageId) {
    var ids = unsettled(caseId, relayMessageId);
    if (ids.isEmpty()) throw new IllegalArgumentException("This quote request was already sent.");
    requirePermitted(caseId, get(caseId, token, ids.getFirst()));
  }

  @Override public Runnable sent(String caseId, String token, String relayMessageId) {
    if (jdbc.update("update quote_requests set state = 'SENT' where case_id = ? and message_id = ? and state = 'HANDED_OFF'",
        caseId, relayMessageId) == 1 && NegotiationConversationService.ACTIVE.contains(negotiation.current(caseId)))
      negotiation.transition(caseId, NegotiationStateService.State.AWAITING_DEALER);
    return null;
  }

  @Override public void unsent(String caseId, String relayMessageId, String state) {
    jdbc.update("update quote_requests set state = ? where case_id = ? and message_id = ? and state = 'HANDED_OFF'",
        state, caseId, relayMessageId);
  }

  private List<String> unsettled(String caseId, String relayMessageId) {
    return jdbc.queryForList("select id from quote_requests where case_id = ? and message_id = ? and state = 'HANDED_OFF'",
        String.class, caseId, relayMessageId);
  }

  @Transactional
  public View view(String caseId, String token) {
    locks.lock(caseId); cases.reopen(caseId, token);
    var dealers = jdbc.query("""
        select d.id, d.name, d.email_language from dealers d join vehicles v on v.case_id = d.case_id
        where d.case_id = ? and exists (
          select 1 from listing_snapshots l where l.case_id = d.case_id and l.dealer_id = d.id and l.vin = v.vin
        ) order by d.name, d.id
        """, (r, n) -> new Dealer(r.getString(1), r.getString(2), r.getString(3)), caseId).stream()
        .filter(dealer -> drafts.context(caseId, dealer.id()).quoteRequestQuestion() != null).toList();
    // A request replaced by a language change was never approved; it stays in the case record, not in the view.
    var ids = jdbc.queryForList("select id from quote_requests where case_id = ? and state <> 'REPLACED' order by created_at, id",
        String.class, caseId);
    var documents = jdbc.query("""
        select a.document_id, a.outcome, o.offer_version, a.ordinal, a.message_id from dealer_relay_attachments a
        left join document_extractions e on e.document_id = a.document_id
        left join dealer_offers o on o.extraction_id = e.id
        where a.case_id = ? order by a.created_at, a.id
        """, (r, n) -> new ReplyDocument(r.getString(1), r.getString(2), (Integer) r.getObject(3),
            r.getInt(4) == -1 ? "MESSAGE_BODY" : "ATTACHMENT", r.getString(5)), caseId);
    return new View(negotiation.current(caseId), ids.stream().map(id -> get(caseId, token, id)).toList(), documents, dealers);
  }

  private Quote get(String caseId, String token, String id) {
    return jdbc.query("select dealer_address, state, message_id, approved_at, draft_id from quote_requests where case_id = ? and id = ?",
        (r, n) -> new Quote(id, r.getString(1), r.getString(2), r.getString(3),
            r.getTimestamp(4) == null ? null : r.getTimestamp(4).toInstant(), drafts.get(caseId, token, r.getString(5))), caseId, id)
        .stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Quote request not found"));
  }
}

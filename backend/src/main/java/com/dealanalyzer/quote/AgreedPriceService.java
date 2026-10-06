package com.dealanalyzer.quote;

import com.dealanalyzer.buyingcase.EphemeralBuyingCaseService;
import com.dealanalyzer.buyingcase.ResourceNotFoundException;
import com.dealanalyzer.buyingcase.persistence.EphemeralCasePersistence;
import com.dealanalyzer.outbound.OutboundDraftService;
import com.dealanalyzer.outbound.OutboundGate;
import com.dealanalyzer.plan.PricePlanService;
import com.dealanalyzer.relay.DealerMessageHandoff;
import com.dealanalyzer.relay.RelayMessageOwner;
import com.dealanalyzer.relay.RelayService;
import com.dealanalyzer.verification.persistence.DealerOfferPersistence;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AgreedPriceService implements RelayMessageOwner {
  private final EphemeralBuyingCaseService cases;
  private final EphemeralCasePersistence locks;
  private final JdbcTemplate jdbc;
  private final OutboundDraftService drafts;
  private final WrittenQuoteEvidence evidence;
  private final DealerOfferPersistence offers;
  private final PricePlanService plans;
  private final NegotiationStateService negotiation;
  private final DealerMessageHandoff handoffs;
  private final JourneyAgreement journeys;
  private final ObjectMapper json;
  private final TransactionTemplate transaction;
  private final jakarta.persistence.EntityManager manager;
  public AgreedPriceService(EphemeralBuyingCaseService cases, EphemeralCasePersistence locks, JdbcTemplate jdbc,
      OutboundDraftService drafts, WrittenQuoteEvidence evidence, DealerOfferPersistence offers,
      PricePlanService plans, NegotiationStateService negotiation, DealerMessageHandoff handoffs, JourneyAgreement journeys,
      ObjectMapper json, PlatformTransactionManager transactions, jakarta.persistence.EntityManager manager) {
    this.cases = cases; this.locks = locks; this.jdbc = jdbc; this.drafts = drafts; this.evidence = evidence;
    this.offers = offers; this.plans = plans; this.negotiation = negotiation; this.handoffs = handoffs;
    this.journeys = journeys; this.json = json; this.transaction = new TransactionTemplate(transactions);
    this.manager = manager;
  }
  public record Prepare(String quoteRequestId, Integer offerVersion) {}
  public record Approval(boolean approved, String text) {}
  public record OtherChoice(JourneyAgreement.Action action) {}
  public record Request(String id, String quoteRequestId, Integer offerVersion, Integer planVersion,
      String evidenceMessageId, String evidenceDocumentId, String kind, String state, String messageId,
      OutboundDraftService.StoredDraft gatedDraft) {}
  public record AgreedPrice(String id, String dealerOfferId, int offerVersion, String baselineId, int baselineVersion,
      String actor, Instant acceptedAt, String evidenceMessageId, String evidenceDocumentId, JourneyAgreement.Result journey) {}
  public record View(List<Request> requests, AgreedPrice agreedPrice) {}
  private record Dealer(String id, String address) {}

  @Transactional
  public Request prepare(String caseId, String token, String key, Prepare request) {
    authorize(caseId, token);
    if (request == null || request.quoteRequestId() == null || key == null || key.isBlank() || key.length() > 200)
      throw new IllegalArgumentException("Choose a sent quote request and provide an idempotency key.");
    String fingerprint = offers.fingerprint(request);
    var previous = jdbc.query("select id, request_fingerprint from quote_acceptance_requests where case_id = ? and idempotency_key = ?",
        (r, n) -> new String[] {r.getString(1), r.getString(2)}, caseId, key);
    if (!previous.isEmpty()) {
      offers.requireMatchingFingerprint(previous.getFirst()[1], fingerprint);
      return get(caseId, token, previous.getFirst()[0]);
    }
    if (readAgreed(caseId) != null) throw new IllegalArgumentException("This case already has an Agreed Price.");
    Dealer dealer = dealer(caseId, request.quoteRequestId());
    var written = evidence.latest(caseId, dealer.id()).orElse(null);
    if (request.offerVersion() != null) {
      var selected = offers.requireOffer(caseId, request.offerVersion());
      if (!offers.latestOffer(caseId).orElseThrow().id().equals(selected.id()))
        throw new IllegalArgumentException("The offer changed. Review the latest verified quote.");
    }
    boolean accepting = request.offerVersion() != null && written != null && written.offer().version() == request.offerVersion();
    Integer planVersion = null;
    if (accepting) {
      var plan = plans.latest(caseId, "PLAN").orElseThrow(() -> new IllegalArgumentException("Review price boundaries for this quote before accepting."));
      if (!plan.payload().at("/dealerOffer/id").asText().equals(written.offer().id()))
        throw new IllegalArgumentException("Review new price boundaries for the current quote before accepting.");
      planVersion = plan.version();
    }
    String text = accepting ? written.text() : WrittenQuoteEvidence.CONFIRMATION;
    var draft = drafts.create(caseId, token, "agreement:" + offers.fingerprint(key), new OutboundGate.Draft(dealer.id(), text));
    String id = UUID.randomUUID().toString();
    jdbc.update("""
        insert into quote_acceptance_requests (id, case_id, quote_request_id, draft_id, offer_version, plan_version,
          evidence_message_id, evidence_document_id, kind, state, idempotency_key, request_fingerprint, created_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?)
        """, id, caseId, request.quoteRequestId(), draft.id(), accepting ? request.offerVersion() : null, planVersion,
        accepting ? written.messageId() : null, accepting ? written.documentId() : null,
        accepting ? "ACCEPTANCE" : "WRITTEN_CONFIRMATION", key, fingerprint, Timestamp.from(Instant.now()));
    return get(caseId, token, id);
  }

  public Request approve(String caseId, String token, String id, Approval approval) {
    boolean reserved = Boolean.TRUE.equals(transaction.execute(status -> {
      authorize(caseId, token);
      Request request = get(caseId, token, id);
      if (approval == null || !approval.approved() || !request.gatedDraft().draft().text().equals(approval.text()))
        throw new IllegalArgumentException("Approve the exact displayed text and baseline before sending.");
      if (!"DRAFT".equals(request.state())) return false;
      requirePermitted(caseId, token, request);
      jdbc.update("update quote_acceptance_requests set state = 'SENDING' where id = ?", id);
      return true;
    }));
    if (!reserved) return transaction.execute(status -> { authorize(caseId, token); return get(caseId, token, id); });
    // The committed reservation prevents a second hand-off after a repeated approval or a crash.
    return transaction.execute(status -> {
      authorize(caseId, token);
      Request request = get(caseId, token, id);
      try { requirePermitted(caseId, token, request); }
      catch (IllegalArgumentException changed) {
        jdbc.update("update quote_acceptance_requests set state = 'BLOCKED' where id = ?", id);
        return get(caseId, token, id);
      }
      // Check the existing approval path before the hand-off; a refused hand-off creates no baseline.
      boolean accepting = "ACCEPTANCE".equals(request.kind());
      if (accepting) plans.reviewApproval(caseId, token, request.planVersion());
      try {
        Dealer dealer = dealer(caseId, request.quoteRequestId());
        var handoff = handoffs.handOff(caseId, dealer.address(), "ACCEPTANCE".equals(request.kind())
            ? "Conditional acceptance of itemized quote" : "Please confirm the quote in writing", request.gatedDraft().draft().text(),
            java.util.Set.of());
        jdbc.update("update quote_acceptance_requests set state = ?, message_id = ? where id = ?", handoff.state(), handoff.messageId(), id);
        if (handoff.sent()) applySent(caseId, token, request);
      } catch (RelayService.RelayRefusedException refused) {
        // An invalid message, or one revealing the Buyer's details, is not handed off.
        jdbc.update("update quote_acceptance_requests set state = ? where id = ?", refused.reason().name(), id);
      }
      return get(caseId, token, id);
    });
  }

  /** The first-send state change: an acceptance creates the Agreed Price; a confirmation request awaits the dealer. */
  private void applySent(String caseId, String token, Request request) {
    String id = request.id();
    if ("ACCEPTANCE".equals(request.kind())) {
      var baseline = plans.approve(caseId, token, "agreement:" + id, new PricePlanService.Approval(request.planVersion(), true));
      manager.flush();
      var offer = offers.requireOffer(caseId, request.offerVersion());
      var journey = journeys.agree(caseId);
      Instant now = com.dealanalyzer.time.PersistedInstant.round(Instant.now());
      String agreedId = UUID.randomUUID().toString();
      jdbc.update("""
          insert into agreed_prices (id, case_id, acceptance_request_id, dealer_offer_id, offer_version, baseline_id,
            baseline_version, actor, accepted_at, evidence_message_id, evidence_document_id, journey_result_json)
          values (?, ?, ?, ?, ?, ?, ?, 'BUYER', ?, ?, ?, ?)
          """, agreedId, caseId, id, offer.id(), offer.version(), baseline.id(), baseline.version(), Timestamp.from(now),
          request.evidenceMessageId(), request.evidenceDocumentId(), write(journey));
      jdbc.update("""
          insert into audit_events (id, case_id, event_type, aggregate_type, aggregate_id, aggregate_version,
            idempotency_key, actor, metadata_json, created_at)
          values (?, ?, 'AGREED_PRICE_ACCEPTED', 'AGREED_PRICE', ?, ?, ?, 'BUYER', ?, ?)
          """, UUID.randomUUID().toString(), caseId, agreedId, offer.version(), id,
          write(java.util.Map.of("baselineId", baseline.id(), "evidenceMessageId", request.evidenceMessageId())), Timestamp.from(now));
      negotiation.transition(caseId, NegotiationStateService.State.AGREED);
    } else negotiation.transition(caseId, NegotiationStateService.State.AWAITING_DEALER);
  }

  // ---------------------------------------------------------------- marked sent or discarded (RelayMessageOwner)

  @Override public boolean owns(String caseId, String relayMessageId) {
    return jdbc.queryForObject("select count(*) from quote_acceptance_requests where case_id = ? and message_id = ?",
        Long.class, caseId, relayMessageId) > 0;
  }

  @Override public void requireResendPermitted(String caseId, String token, String relayMessageId) {
    var ids = unsettled(caseId, relayMessageId);
    if (ids.isEmpty()) throw new IllegalArgumentException("This message was already sent.");
    Request request = get(caseId, token, ids.getFirst());
    requirePermitted(caseId, token, request);
  }

  @Override public Runnable sent(String caseId, String token, String relayMessageId) {
    var ids = unsettled(caseId, relayMessageId);
    if (ids.isEmpty()) return null;
    jdbc.update("update quote_acceptance_requests set state = 'SENT' where id = ?", ids.getFirst());
    // The dealer has the message. Its state change applies while negotiation can still reach it.
    var state = negotiation.current(caseId);
    if (readAgreed(caseId) == null && NegotiationConversationService.ACTIVE.contains(state))
      applySent(caseId, token, get(caseId, token, ids.getFirst()));
    return null;
  }

  @Override public void unsent(String caseId, String relayMessageId, String state) {
    jdbc.update("update quote_acceptance_requests set state = ? where case_id = ? and message_id = ? and state = 'HANDED_OFF'",
        state, caseId, relayMessageId);
  }

  private List<String> unsettled(String caseId, String relayMessageId) {
    return jdbc.queryForList("select id from quote_acceptance_requests where case_id = ? and message_id = ? and state = 'HANDED_OFF'",
        String.class, caseId, relayMessageId);
  }

  private void requirePermitted(String caseId, String token, Request request) {
    var context = drafts.context(caseId, request.gatedDraft().draft().dealerId());
    if (!request.gatedDraft().decision().pass() || !new OutboundGate().check(request.gatedDraft().draft(), context).pass()
        || context.mandate() == null || !context.mandate().id().equals(request.gatedDraft().mandateId())
        || negotiation.current(caseId) == NegotiationStateService.State.AGREED)
      throw new IllegalArgumentException("The current mandate or policy no longer permits this message. Prepare a new request.");
    var state = negotiation.current(caseId);
    if (state == NegotiationStateService.State.STOPPED) throw new IllegalArgumentException("Negotiation has stopped.");
    if (state == NegotiationStateService.State.PAUSED) throw new IllegalArgumentException("Negotiation is paused. Resume it before sending.");
    if ("ACCEPTANCE".equals(request.kind())) {
      var written = evidence.latest(caseId, request.gatedDraft().draft().dealerId())
          .orElseThrow(() -> new IllegalArgumentException("Review and verify a current itemized written quote first."));
      if (written.offer().version() != request.offerVersion() || !written.messageId().equals(request.evidenceMessageId())
          || !written.documentId().equals(request.evidenceDocumentId()))
        throw new IllegalArgumentException("Written quote evidence changed. Review it again.");
      var plan = plans.get(caseId, token, "PLAN", request.planVersion());
      plans.reviewApproval(caseId, token, request.planVersion());
      if (!plan.payload().at("/dealerOffer/id").asText().equals(written.offer().id())
          || plans.latest(caseId, "PLAN").orElseThrow().version() != plan.version())
        throw new IllegalArgumentException("Price boundaries changed. Review them again.");
      long selling = WrittenQuoteEvidence.amount(written.offer().terms(), "SELLING_PRICE");
      long otd = WrittenQuoteEvidence.amount(written.offer().terms(), "OUT_THE_DOOR_TOTAL");
      var otdBoundary = plan.payload().at("/plan/outTheDoor/walkAwayMinor");
      var buyerOtd = plan.payload().at("/plan/buyerMaxOutTheDoorPriceMinor");
      if (selling > context.mandate().ceilingMinor() || selling > plan.payload().at("/plan/sellingPrice/walkAwayMinor").asLong()
          || otdBoundary.isNumber() && otd > otdBoundary.asLong() || buyerOtd.isNumber() && otd > buyerOtd.asLong())
        throw new IllegalArgumentException("The quote exceeds your current price limits. Review your boundaries before accepting.");
    }
  }

  @Transactional
  public View view(String caseId, String token) {
    authorize(caseId, token);
    return new View(jdbc.queryForList("select id from quote_acceptance_requests where case_id = ? order by created_at, id", String.class, caseId)
        .stream().map(id -> get(caseId, token, id)).toList(), readAgreed(caseId));
  }
  @Transactional
  public JourneyAgreement.Result chooseOther(String caseId, String token, String otherCaseId, OtherChoice choice) {
    authorize(caseId, token);
    AgreedPrice agreed = readAgreed(caseId);
    if (agreed == null || !agreed.journey().journeyLinked() || caseId.equals(otherCaseId)
        || choice == null || choice.action() == null || agreed.journey().otherOpenNegotiations().stream()
            .noneMatch(other -> other.caseId().equals(otherCaseId)))
      throw new IllegalArgumentException("Choose an open negotiation in this agreed journey.");
    return journeys.choose(caseId, otherCaseId, choice.action());
  }
  private void authorize(String caseId, String token) { locks.lock(caseId); cases.reopen(caseId, token); }
  private Dealer dealer(String caseId, String quoteId) {
    return jdbc.query("""
        select d.dealer_id, q.dealer_address from quote_requests q join outbound_drafts d on d.id = q.draft_id
        where q.case_id = ? and q.id = ? and q.state = 'SENT'
        """, (r, n) -> new Dealer(r.getString(1), r.getString(2)), caseId, quoteId).stream().findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Choose a sent request from this dealer conversation."));
  }
  private Request get(String caseId, String token, String id) {
    return jdbc.query("""
        select quote_request_id, offer_version, plan_version, evidence_message_id, evidence_document_id, kind, state, message_id, draft_id
        from quote_acceptance_requests where case_id = ? and id = ?
        """, (r, n) -> new Request(id, r.getString(1), (Integer) r.getObject(2), (Integer) r.getObject(3), r.getString(4),
            r.getString(5), r.getString(6), r.getString(7), r.getString(8), drafts.get(caseId, token, r.getString(9))), caseId, id)
        .stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Acceptance request not found"));
  }
  private AgreedPrice readAgreed(String caseId) {
    return jdbc.query("""
        select id, dealer_offer_id, offer_version, baseline_id, baseline_version, actor, accepted_at,
          evidence_message_id, evidence_document_id, journey_result_json from agreed_prices where case_id = ?
        """, (r, n) -> new AgreedPrice(r.getString(1), r.getString(2), r.getInt(3), r.getString(4), r.getInt(5), r.getString(6),
            r.getTimestamp(7).toInstant(), r.getString(8), r.getString(9), journeys.current(caseId, readJourney(r.getString(10)))), caseId).stream().findFirst().orElse(null);
  }
  private String write(Object value) {
    try { return json.writeValueAsString(value); }
    catch (java.io.IOException failure) { throw new IllegalStateException("Unable to store acceptance", failure); }
  }
  private JourneyAgreement.Result readJourney(String value) {
    try { return json.readValue(value, JourneyAgreement.Result.class); }
    catch (java.io.IOException failure) { throw new IllegalStateException("Unable to read acceptance", failure); }
  }
}

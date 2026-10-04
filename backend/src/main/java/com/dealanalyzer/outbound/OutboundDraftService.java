package com.dealanalyzer.outbound;

import com.dealanalyzer.buyingcase.EphemeralBuyingCaseService;
import com.dealanalyzer.account.AccountOutboundPrivacy;
import com.dealanalyzer.buyingcase.ResourceNotFoundException;
import com.dealanalyzer.buyingcase.persistence.EphemeralCasePersistence;
import com.dealanalyzer.mandate.MandateService;
import com.dealanalyzer.verification.persistence.DealerOfferPersistence;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stores evaluations only. A saved pass is evidence about this snapshot, never permission to send. */
@Service
public class OutboundDraftService {
  private final EphemeralBuyingCaseService cases;
  private final EphemeralCasePersistence locks;
  private final MandateService mandates;
  private final DealerOfferPersistence offers;
  private final EntityManager manager;
  private final ObjectMapper json;
  private final OutboundGate gate = new OutboundGate();
  private final AccountOutboundPrivacy privacy;
  private final com.dealanalyzer.quote.WrittenQuoteEvidence writtenQuotes;
  private final org.springframework.beans.factory.ObjectProvider<OutboundCaseLanguage> caseLanguage;

  public OutboundDraftService(EphemeralBuyingCaseService cases, EphemeralCasePersistence locks,
      MandateService mandates, DealerOfferPersistence offers, EntityManager manager, ObjectMapper json,
      AccountOutboundPrivacy privacy, com.dealanalyzer.quote.WrittenQuoteEvidence writtenQuotes,
      org.springframework.beans.factory.ObjectProvider<OutboundCaseLanguage> caseLanguage) {
    this.cases = cases; this.locks = locks; this.mandates = mandates; this.offers = offers;
    this.manager = manager; this.json = json;
    this.privacy = privacy;
    this.writtenQuotes = writtenQuotes;
    this.caseLanguage = caseLanguage;
  }

  public record StoredDraft(String id, String caseId, OutboundGate.Draft draft,
      String mandateId, OutboundGate.Decision decision, Instant createdAt) {}

  @Transactional
  public StoredDraft create(String caseId, String token, String key, OutboundGate.Draft draft) {
    locks.lock(caseId);
    cases.reopen(caseId, token);
    if (draft == null || draft.dealerId() == null || draft.text() == null || draft.text().isBlank()
        || draft.text().length() > 10000 || key == null || key.isBlank() || key.length() > 200)
      throw new IllegalArgumentException("A dealer, a message of at most 10,000 characters and an idempotency key are required");
    String fingerprint = offers.fingerprint(draft);
    var previous = manager.createNativeQuery("select id, request_fingerprint from outbound_drafts where case_id = :caseId and idempotency_key = :key")
        .setParameter("caseId", caseId).setParameter("key", key).getResultList();
    if (!previous.isEmpty()) {
      Object[] row = (Object[]) previous.getFirst();
      offers.requireMatchingFingerprint(row[1].toString(), fingerprint);
      return get(caseId, token, row[0].toString());
    }
    long dealers = ((Number) manager.createNativeQuery("select count(*) from dealers where case_id = :caseId and id = :dealer")
        .setParameter("caseId", caseId).setParameter("dealer", draft.dealerId()).getSingleResult()).longValue();
    if (dealers != 1) throw new ResourceNotFoundException("Dealer not found in this case");
    var context = context(caseId, draft.dealerId());
    var decision = gate.check(draft, context);
    String id = UUID.randomUUID().toString();
    Instant now = com.dealanalyzer.time.PersistedInstant.round(Instant.now());
    String mandateId = context.mandate() == null ? null : context.mandate().id();
    manager.createNativeQuery("""
        insert into outbound_drafts (id, case_id, dealer_id, mandate_id, draft_text, gate_version,
          decision_json, context_json, idempotency_key, request_fingerprint, created_at)
        values (:id, :caseId, :dealer, :mandate, :text, :version, :decision, :context, :key, :fingerprint, :now)
        """).setParameter("id", id).setParameter("caseId", caseId).setParameter("dealer", draft.dealerId())
        .setParameter("mandate", mandateId).setParameter("text", draft.text()).setParameter("version", OutboundGate.VERSION)
        .setParameter("decision", write(decision)).setParameter("context", write(snapshot(context)))
        .setParameter("key", key).setParameter("fingerprint", fingerprint).setParameter("now", now).executeUpdate();
    return new StoredDraft(id, caseId, draft, mandateId, decision, now);
  }

  @Transactional(readOnly = true)
  public List<StoredDraft> list(String caseId, String token) {
    cases.reopen(caseId, token);
    return read(caseId, null);
  }

  @Transactional(readOnly = true)
  public StoredDraft get(String caseId, String token, String id) {
    cases.reopen(caseId, token);
    return read(caseId, id).stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Outbound draft not found"));
  }

  /** Called again by future sending code, under its case lock, to evaluate current policy. */
  @Transactional(readOnly = true)
  public OutboundGate.GateContext context(String caseId) {
    return context(caseId, null);
  }

  @Transactional(readOnly = true)
  public OutboundGate.GateContext context(String caseId, String dealerId) {
    var mandate = mandates.activeMandate(caseId).orElse(null);
    var amounts = new HashSet<Long>();
    offers.latestOffer(caseId).ifPresent(offer -> collectAmounts(offer.terms(), mandate == null ? "" : mandate.currency(), amounts));
    var personal = new HashSet<>(privacy.knownValues(caseId));
    for (Object postal : manager.createNativeQuery("select buyer_zip from analysis_cases where id = :caseId")
        .setParameter("caseId", caseId).getResultList()) if (postal != null) personal.add(postal.toString());
    for (Object email : manager.createNativeQuery("select email from case_recovery_contacts where case_id = :caseId")
        .setParameter("caseId", caseId).getResultList()) personal.add(email.toString());
    // No disclosure release exists until ticket 65. No unverified profile statement is authorized.
    JsonNode report = null;
    var reports = manager.createNativeQuery("select payload_json from analysis_reports where case_id = :caseId order by report_version desc")
        .setParameter("caseId", caseId).setMaxResults(1).getResultList();
    if (!reports.isEmpty()) report = parse(reports.getFirst().toString(), JsonNode.class);
    var questions = new HashSet<String>();
    String quoteRequestQuestion = null;
    if (dealerId != null) {
      List<?> listings = manager.createNativeQuery("select v.vin, l.listing_url from vehicles v join listing_snapshots l on l.case_id = v.case_id where v.case_id = :caseId and l.dealer_id = :dealer and l.vin = v.vin order by l.retrieved_at desc, l.id")
          .setParameter("caseId", caseId).setParameter("dealer", dealerId).setMaxResults(1).getResultList();
      if (!listings.isEmpty()) {
        Object[] row = (Object[]) listings.getFirst();
        try {
          quoteRequestQuestion = com.dealanalyzer.quote.QuoteRequestText.render((String) row[0], (String) row[1]);
          questions.add(quoteRequestQuestion);
          questions.add(com.dealanalyzer.quote.WrittenQuoteEvidence.CONFIRMATION);
          writtenQuotes.latest(caseId, dealerId).ifPresent(evidence -> {
            Long selling = com.dealanalyzer.quote.WrittenQuoteEvidence.amount(evidence.offer().terms(), "SELLING_PRICE");
            if (mandate != null && selling != null && selling <= mandate.ceilingMinor()) questions.add(evidence.text());
          });
        }
        catch (IllegalArgumentException unsupported) { /* Invalid source references authorize no language. */ }
      }
      caseLanguage.orderedStream().forEach(language -> questions.addAll(language.paragraphs(caseId, dealerId)));
    }
    return new OutboundGate.GateContext(mandate, amounts, Set.of(), personal, Set.of(), report, questions, quoteRequestQuestion);
  }

  private Map<String, Object> snapshot(OutboundGate.GateContext context) {
    var snapshot = new LinkedHashMap<String, Object>();
    snapshot.put("mandate", context.mandate());
    snapshot.put("verifiedAmountsMinor", context.verifiedAmountsMinor().stream().sorted().toList());
    snapshot.put("verifiedDisclosures", context.verifiedDisclosures());
    snapshot.put("caseQuestions", context.caseQuestions());
    snapshot.put("quoteRequestQuestion", context.quoteRequestQuestion());
    snapshot.put("personalValueCount", context.personalValues().size());
    snapshot.put("releasedForDealer", context.releasedForDealer());
    snapshot.put("reportFingerprint", offers.fingerprint(context.finalizedReport()));
    return snapshot;
  }

  private static void collectAmounts(JsonNode node, String currency, Set<Long> amounts) {
    if (node.isObject()) {
      if (node.path("buyerVerified").asBoolean() && node.path("unit").asText().equals(currency + "_MINOR")) {
        try { amounts.add(Long.parseLong(node.path("value").asText())); }
        catch (NumberFormatException invalid) { /* Unusable facts authorize no amount. */ }
      }
      node.properties().forEach(entry -> {
        if (entry.getKey().equals("amountMinor") && node.path("buyerVerified").asBoolean()
            && entry.getValue().isIntegralNumber() && currency.equals(node.path("currency").asText()))
          amounts.add(entry.getValue().longValue());
        else collectAmounts(entry.getValue(), currency, amounts);
      });
    } else if (node.isArray()) node.forEach(child -> collectAmounts(child, currency, amounts));
  }

  private List<StoredDraft> read(String caseId, String id) {
    var query = manager.createNativeQuery("select id, dealer_id, mandate_id, draft_text, decision_json, created_at from outbound_drafts where case_id = :caseId"
        + (id == null ? "" : " and id = :id") + " order by created_at, id").setParameter("caseId", caseId);
    if (id != null) query.setParameter("id", id);
    List<?> rows = query.getResultList();
    return rows.stream().map(item -> {
      Object[] row = (Object[]) item;
      Instant at = row[5] instanceof java.time.OffsetDateTime date ? date.toInstant()
          : row[5] instanceof Instant instant ? instant : ((java.sql.Timestamp) row[5]).toInstant();
      return new StoredDraft(row[0].toString(), caseId, new OutboundGate.Draft(row[1].toString(), row[3].toString()),
          row[2] == null ? null : row[2].toString(), parse(row[4].toString(), OutboundGate.Decision.class), at);
    }).toList();
  }
  private String write(Object value) {
    try { return json.writeValueAsString(value); }
    catch (java.io.IOException failure) { throw new IllegalStateException("Unable to store outbound evaluation", failure); }
  }
  private <T> T parse(String value, Class<T> type) {
    try { return json.readValue(value, type); }
    catch (java.io.IOException failure) { throw new IllegalStateException("Unable to read outbound evaluation", failure); }
  }
}

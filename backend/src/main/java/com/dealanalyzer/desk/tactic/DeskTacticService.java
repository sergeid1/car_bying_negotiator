package com.dealanalyzer.desk.tactic;

import com.dealanalyzer.extraction.persistence.DocumentExtractionPersistence.ExtractionView;
import com.dealanalyzer.loan.ChosenLoanOffers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Case-scoped adapter; callers enforce case access and purchase gates before projecting findings. */
@Service
public class DeskTacticService {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final ChosenLoanOffers chosenLoans;
  public DeskTacticService(JdbcTemplate jdbc, ObjectMapper json, ChosenLoanOffers chosenLoans) {
    this.jdbc = jdbc; this.json = json; this.chosenLoans = chosenLoans;
  }

  public List<DeskTacticPolicy.Finding> findings(String caseId, JsonNode terms, JsonNode agreedTerms) {
    return findings(caseId, terms, agreedTerms, null);
  }

  /**
   * The Buyer's own financing for the case: the later of the latest saved outside Finance Offer (FIN-04) and the
   * loan offer the Buyer chose (LOAN-03), or null. An expired chosen offer is not used.
   */
  public DeskTacticPolicy.BuyerOffer ownOffer(String caseId) {
    record Saved(Instant createdAt, DeskTacticPolicy.BuyerOffer offer) {}
    var saved = jdbc.query("select version, payload_json, created_at from outside_finance_offers where case_id = ? order by version desc fetch first 1 rows only",
        (r, n) -> {
          try {
            JsonNode loan = json.readTree(r.getString(2));
            return new Saved(r.getTimestamp(3).toInstant(), new DeskTacticPolicy.BuyerOffer(
                loan.path("aprPercent").isNumber() ? loan.path("aprPercent").decimalValue() : null,
                loan.path("termMonths").isInt() ? loan.path("termMonths").intValue() : null,
                json.createObjectNode().put("source", "outside_finance_offers").put("version", r.getInt(1)).set("inputs", loan)));
          } catch (java.io.IOException invalid) { throw new IllegalStateException("Unable to read outside Finance Offer", invalid); }
        }, caseId).stream().findFirst().orElse(null);
    var chosen = chosenLoans.forCase(caseId, Instant.now()).orElse(null);
    if (chosen == null || saved != null && saved.createdAt().isAfter(chosen.chosenAt())) return saved == null ? null : saved.offer();
    var evidence = json.createObjectNode().put("source", "chosen_loan_offer").put("offerId", chosen.offerId())
        .put("lender", chosen.lender()).put("offerSource", chosen.source()).put("amountMinor", chosen.amountMinor())
        .put("aprPercent", chosen.aprPercent()).put("termMonths", chosen.termMonths());
    if (chosen.expiresAt() != null) evidence.put("expiresAt", chosen.expiresAt().toString());
    return new DeskTacticPolicy.BuyerOffer(chosen.aprPercent(), chosen.termMonths(), evidence);
  }

  public List<DeskTacticPolicy.Finding> findings(String caseId, JsonNode terms, JsonNode agreedTerms, BigDecimal ownApr) {
    var buyer = ownOffer(caseId);
    if (buyer == null && agreedTerms != null) {
      var statedTerms = new java.util.ArrayList<JsonNode>();
      for (JsonNode term : agreedTerms.path("candidates")) if ("TERM_MONTHS".equals(term.path("field").asText())) statedTerms.add(term);
      if (statedTerms.size() == 1 && List.of("MONTH", "MONTHS").contains(statedTerms.getFirst().path("unit").asText())) {
        JsonNode term = statedTerms.getFirst();
        try { buyer = new DeskTacticPolicy.BuyerOffer(null, Integer.valueOf(term.path("value").asText()),
            json.createObjectNode().put("source", "agreed_baseline").set("term", term)); }
        catch (NumberFormatException invalid) { /* No stated term usable. */ }
      }
    }
    if (ownApr != null) buyer = new DeskTacticPolicy.BuyerOffer(ownApr, buyer == null ? null : buyer.termMonths(),
        json.createObjectNode().put("source", "buyer_entered_apr").put("aprPercent", ownApr).set("savedReference", buyer == null ? null : buyer.evidence()));
    return new DeskTacticPolicy().evaluate(new DeskTacticPolicy.Input(terms, buyer));
  }

  public List<DeskTacticPolicy.Finding> findings(String caseId, JsonNode terms, JsonNode agreedTerms, BigDecimal ownApr,
      com.dealanalyzer.trade.desk.TradeDeskCheck.Result trade) {
    var findings = new java.util.ArrayList<>(findings(caseId, terms, agreedTerms, ownApr));
    findings.addAll(tradeFindings(terms, trade));
    return List.copyOf(findings);
  }

  public List<DeskTacticPolicy.Finding> tradeFindings(JsonNode terms, com.dealanalyzer.trade.desk.TradeDeskCheck.Result trade) {
    return new DeskTacticPolicy().tradeFindings(new DeskTacticPolicy.Input(terms, null), trade, json.valueToTree(trade));
  }

  public JsonNode asRead(ExtractionView extraction) {
    var terms = json.createObjectNode();
    var candidates = terms.putArray("candidates");
    if (extraction != null) for (var candidate : extraction.candidates()) {
      var correction = candidate.currentCorrection();
      candidates.addObject().put("field", candidate.field())
          .put("value", correction == null ? candidate.normalizedValue() : correction.normalizedValue())
          .put("unit", correction == null ? candidate.normalizedUnit() : correction.normalizedUnit())
          .put("buyerVerified", false).set("evidence", json.valueToTree(candidate.evidence()));
    }
    return terms;
  }
}

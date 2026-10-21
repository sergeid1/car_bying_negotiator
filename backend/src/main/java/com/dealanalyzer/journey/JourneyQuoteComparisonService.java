package com.dealanalyzer.journey;

import com.dealanalyzer.account.AccountJourneyAccess;
import com.dealanalyzer.economics.persistence.CashEconomicsPersistence;
import com.dealanalyzer.report.AmountEvidence;
import com.dealanalyzer.report.FindingText;
import com.dealanalyzer.report.FindingText.Finding;
import com.dealanalyzer.verification.persistence.DealerOfferPersistence;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Buyer-owned read view. Quoted amounts are never replaced by calculated or advertised prices. */
@Service
public class JourneyQuoteComparisonService {
  private final AccountJourneyAccess accounts;
  private final BuyingJourneyRepository journeys;
  private final JdbcTemplate jdbc;
  private final DealerOfferPersistence offers;
  private final ObjectMapper json;
  private final CashEconomicsPersistence economics;

  JourneyQuoteComparisonService(AccountJourneyAccess accounts, BuyingJourneyRepository journeys,
      JdbcTemplate jdbc, DealerOfferPersistence offers, ObjectMapper json, CashEconomicsPersistence economics) {
    this.accounts = accounts; this.journeys = journeys; this.jdbc = jdbc; this.offers = offers; this.json = json;
    this.economics = economics;
  }

  /**
   * The English fields stay as they were; each gains a coded twin (ticket 147) that the client renders in the viewer's
   * language. {@code category} is the comparable charge the amount belongs to, or MARKET for a stored market estimate.
   */
  public record Amount(String label, long minorUnits, String currency, AmountEvidence.Explanation evidence,
      String category, Finding name) {}
  public record Car(String candidateId, String caseId, String title, String state, Integer offerVersion,
      List<Amount> amounts, List<String> missing, List<String> noneListed, String marketPosition, Integer reportVersion,
      Finding stateName, List<Finding> missingNames, List<String> noneListedCategories, Finding marketName) {}

  /** The comparable charges, in display order: category code, English name, line-item category, total field. */
  private record Charge(String code, String label, String lineCategory, String field) {}
  private static final List<Charge> CHARGES = List.of(
      new Charge("SELLING_PRICE", "Selling price", "SELLING_PRICE", "SELLING_PRICE"),
      new Charge("DEALER_FEES", "Dealer fees", "DEALER_FEE", "DEALER_CHARGES_TOTAL"),
      new Charge("ADD_ONS", "Add-ons", "ADD_ON", "ADD_ON_TOTAL"),
      new Charge("TAXES", "Taxes", "TAX", "TAX_TOTAL"),
      new Charge("GOVERNMENT_CHARGES", "Government charges", "GOVERNMENT_CHARGE", "GOVERNMENT_CHARGES_TOTAL"),
      new Charge("OUT_THE_DOOR_TOTAL", "Out-the-door total", null, "OUT_THE_DOOR_TOTAL"));

  private static Finding named(String code, String text) { return new Finding("COMPARE_" + code, Map.of(), text); }

  private static Finding named(String code, String text, Map<String, Object> parameters) {
    return new Finding("COMPARE_" + code, parameters, text);
  }

  /** Coded twins for the English reasons, built beside them so the two lists always agree. */
  private record Missing(List<String> text, List<Finding> names) {
    Missing() { this(new ArrayList<>(), new ArrayList<>()); }
    void add(Finding name) { text.add(name.text()); names.add(name); }
    void add(Charge charge) { add(named(charge.code(), charge.label())); }
    void remove(Charge charge) { int at = text.indexOf(charge.label()); if (at >= 0) { text.remove(at); names.remove(at); } }
  }
  public record Comparison(List<Car> cars, boolean otdComparable) {}
  private record Link(String candidateId, String caseId, String candidateJson, String state, boolean blocked) {}

  @Transactional(readOnly = true)
  public Comparison view(String session, String journeyId) {
    String owner = accounts.accountId(session);
    journeys.findByIdAndAccountId(journeyId, owner).orElseThrow(JourneyProblem::notFound);
    var links = jdbc.query("""
        select l.candidate_id, l.case_id, v.candidate_json, s.state, c.core_review_blocked, c.status
        from journey_candidate_cases l
        join journey_candidates v on v.id = l.candidate_id and v.account_id = l.account_id and v.journey_id = l.journey_id
        join buyer_account_cases a on a.case_id = l.case_id and a.account_id = l.account_id
        join buyer_accounts b on b.id = l.account_id and b.lifecycle = 'ACTIVE'
        join analysis_cases c on c.id = l.case_id and c.lifecycle = 'ACTIVE' and c.access_expires_at > CURRENT_TIMESTAMP
        left join case_negotiation_states s on s.case_id = l.case_id
        where l.journey_id = ? and l.account_id = ?
        order by case when v.shortlist_position is null then 1 else 0 end, v.shortlist_position, l.created_at, l.case_id
        """, (r, n) -> new Link(r.getString(1), r.getString(2), r.getString(3), r.getString(4),
            !"READY_TO_ANALYZE".equals(r.getString(6))
                && (!List.of("COMPLETE", "PARTIAL").contains(r.getString(6)) || r.getBoolean(5))), journeyId, owner);
    var cars = links.stream().map(this::car).toList();
    return new Comparison(cars, !cars.isEmpty() && cars.stream().allMatch(car -> car.missing().isEmpty()));
  }

  private Car car(Link link) {
    String title = read(link.candidateJson()).path("title").asText("Candidate vehicle");
    var offer = offers.latestOffer(link.caseId()).filter(o -> "VERIFIED".equals(o.status())).orElse(null);
    var amounts = new ArrayList<Amount>();
    var missing = new Missing();
    var noneListed = new ArrayList<Charge>();
    Finding market = named("MARKET_ANALYZE", "Market position unavailable; analyze the current verified offer.");
    Integer reportVersion = null;
    if (offer == null || link.blocked()) {
      CHARGES.forEach(missing::add);
      boolean received = !jdbc.queryForList("select id from document_extractions where case_id = ?", String.class, link.caseId()).isEmpty();
      Finding state = received || offer != null ? named("STATE_UNVERIFIED", "Quote unverified") : named("STATE_AWAITING", "Awaiting quote");
      return car(link, title, state, null, amounts, missing, noneListed, market, null);
    }
    String document = jdbc.queryForList("select document_id from document_extractions where case_id = ? and id = ?",
        String.class, link.caseId(), offer.extractionId()).stream().findFirst().orElse(null);
    JsonNode terms = offer.terms();
    for (Charge charge : CHARGES) collect(amounts, missing, terms, charge, document, offer.version());
    // Read the existing calculation for this exact offer; comparison never recalculates charges or tax.
    var cash = economics.forOffer(offer.id()).orElse(null);
    JsonNode reconciliation = cash == null ? json.missingNode() : cash.result().path("reconciliation");
    boolean quoted = amounts.stream().anyMatch(amount -> amount.category().equals("OUT_THE_DOOR_TOTAL"));
    boolean reconciled = quoted && reconciliation.path("quotedTotal").isObject()
        && reconciliation.path("withinTolerance").asBoolean();
    if (!quoted) missing.add(named("NO_QUOTED_OTD", "No Buyer-verified quoted OTD"));
    else if (cash == null) missing.add(named("NO_CASH", "Cash reconciliation unavailable; analyze the verified offer"));
    else if (!reconciliation.path("quotedTotal").isObject()) missing.add(named("NO_RECONCILED_OTD", "No quoted OTD in the cash reconciliation"));
    else if (!reconciliation.path("withinTolerance").asBoolean()) {
      JsonNode difference = reconciliation.at("/discrepancy/minorUnits");
      String currency = reconciliation.at("/discrepancy/currency").asText("USD");
      missing.add(difference.isIntegralNumber()
          ? named("OTD_OFF_BY", "Figures do not add up to the quoted OTD by "
              + java.math.BigDecimal.valueOf(difference.asLong(), 2).abs().toPlainString() + " " + currency,
              Map.of("difference", Map.of("minorUnits", Math.abs(difference.asLong()), "currency", currency)))
          : named("OTD_OFF", "Figures do not add up to the quoted OTD; reconciliation difference unavailable"));
    }
    if (reconciled) for (Charge charge : CHARGES.subList(1, 5)) {
      boolean listed = amounts.stream().anyMatch(amount -> amount.category().equals(charge.code()));
      if (!listed && absent(terms, charge)) { missing.remove(charge); noneListed.add(charge); }
    }
    if (!jdbc.queryForList("select id from document_extractions where case_id = ? and id = ? and suspected_missing_pages = true",
        String.class, link.caseId(), offer.extractionId()).isEmpty()) missing.add(named("MISSING_PAGES", "Suspected missing document pages"));
    for (JsonNode line : terms.path("lineItems")) {
      if (!List.of("SELLING_PRICE", "DEALER_FEE", "ADD_ON", "TAX", "GOVERNMENT_CHARGE", "DISCOUNT", "REBATE", "TRADE", "CREDIT").contains(line.path("category").asText())) {
        String label = line.path("originalLabel").asText("charge");
        missing.add(named("UNCLASSIFIED", "Unclassified charge: " + label, Map.of("paperworkLabel", label)));
      }
    }
    var reports = jdbc.queryForList("select payload_json from analysis_reports where case_id = ? order by report_version desc", String.class, link.caseId());
    if (!reports.isEmpty()) {
      JsonNode report = read(reports.getFirst());
      if (offer.id().equals(report.at("/evidence/dealerOffer/id").asText())) {
        reportVersion = report.path("version").asInt();
        if ("AVAILABLE".equals(report.at("/analysis/marketAvailability").asText())) {
          market = switch (report.at("/analysis/dealRating").asText()) {
            case "STRONG_DEAL" -> named("MARKET_BELOW", "Selling price below the stored market range.");
            case "FAIR_NEGOTIATE" -> named("MARKET_WITHIN", "Selling price within the stored market range.");
            case "POOR_DEAL" -> named("MARKET_ABOVE", "Selling price above the stored market range.");
            default -> named("MARKET_NOT_STORED", "Market position unavailable in the stored report.");
          };
          for (JsonNode amount : report.at("/analysis/materialAmounts")) {
            if (!"marketAssessment".equals(amount.path("source").asText()) || !amount.path("minorUnits").isIntegralNumber()
                || "UNAVAILABLE".equals(amount.path("precision").asText())) continue;
            String label = amount.path("label").asText();
            Finding name = java.util.Objects.requireNonNullElse(FindingText.identify(label), new Finding("UNKNOWN_LABEL", Map.of(), label));
            amounts.add(new Amount(label, amount.path("minorUnits").asLong(), amount.path("currency").asText("USD"),
                new AmountEvidence.Explanation(null, amount.path("sourceNote").asText("Stored market estimate; not a dealer quote."), null),
                "MARKET", name));
          }
        }
      }
    }
    Finding state = "AGREED".equals(link.state()) ? named("STATE_AGREED", "Agreed") : named("STATE_VERIFIED", "Verified quote");
    return car(link, title, state, offer.version(), amounts, missing, noneListed, market, reportVersion);
  }

  private static Car car(Link link, String title, Finding state, Integer offerVersion, List<Amount> amounts, Missing missing,
      List<Charge> noneListed, Finding market, Integer reportVersion) {
    return new Car(link.candidateId(), link.caseId(), title, state.text(), offerVersion, List.copyOf(amounts),
        List.copyOf(missing.text()), noneListed.stream().map(Charge::label).toList(), market.text(), reportVersion,
        state, List.copyOf(missing.names()), noneListed.stream().map(Charge::code).toList(), market);
  }

  private boolean absent(JsonNode terms, Charge charge) {
    for (JsonNode line : terms.path("lineItems")) if (charge.lineCategory().equals(line.path("category").asText())) return false;
    for (JsonNode term : terms.path("candidates")) if (charge.field().equals(term.path("field").asText())) return false;
    return true;
  }

  private void collect(List<Amount> amounts, Missing missing, JsonNode terms, Charge charge, String document, int version) {
    var selected = new ArrayList<JsonNode>();
    if (charge.lineCategory() != null) for (JsonNode line : terms.path("lineItems")) {
      if (charge.lineCategory().equals(line.path("category").asText())) selected.add(line);
    }
    if (selected.isEmpty()) for (JsonNode term : terms.path("candidates")) {
      if (charge.field().equals(term.path("field").asText())) selected.add(term);
    }
    if (selected.isEmpty()) { missing.add(charge); return; }
    if (!selected.getFirst().has("amountMinor") && selected.size() != 1) { missing.add(charge); return; }
    for (JsonNode term : selected) {
      boolean line = term.has("amountMinor");
      Long value = null;
      try {
        if (term.path("buyerVerified").asBoolean() && (line ? term.path("amountMinor").isIntegralNumber()
            && "USD".equals(term.path("currency").asText()) : "USD_MINOR".equals(term.path("unit").asText())))
          value = line ? term.path("amountMinor").asLong() : Long.parseLong(term.path("value").asText());
      } catch (NumberFormatException invalid) { /* Missing, never zero. */ }
      if (value == null || value < 0) { missing.add(charge); continue; }
      var result = json.createObjectNode();
      var entry = result.putArray("lineItems").addObject();
      entry.put("evidence", term.path(line ? "sourceLineItemId" : "sourceCandidateId").asText());
      var evidence = new AmountEvidence().cash(result, terms, document, version).find("cashEconomics", "/result/lineItems/0/amount");
      Finding category = named(charge.code(), charge.label());
      // The paperwork's own label is quoted as printed, never translated.
      Finding name = line ? named("LINE", charge.label() + ": " + term.path("originalLabel").asText(),
          Map.of("category", category, "paperworkLabel", term.path("originalLabel").asText())) : category;
      amounts.add(new Amount(name.text(), value, "USD", evidence, charge.code(), name));
    }
  }

  private JsonNode read(String value) {
    try { return json.readTree(value); }
    catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException("Stored comparison evidence is unreadable", invalid); }
  }
}

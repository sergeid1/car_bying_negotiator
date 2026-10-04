package com.dealanalyzer.journey;

import com.dealanalyzer.account.AccountJourneyAccess;
import com.dealanalyzer.economics.persistence.CashEconomicsPersistence;
import com.dealanalyzer.report.AmountEvidence;
import com.dealanalyzer.verification.persistence.DealerOfferPersistence;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
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

  public record Amount(String label, long minorUnits, String currency, AmountEvidence.Explanation evidence) {}
  public record Car(String candidateId, String caseId, String title, String state, Integer offerVersion,
      List<Amount> amounts, List<String> missing, List<String> noneListed, String marketPosition, Integer reportVersion) {}
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
    var missing = new ArrayList<String>();
    var noneListed = new ArrayList<String>();
    String market = "Market position unavailable; analyze the current verified offer.";
    Integer reportVersion = null;
    if (offer == null || link.blocked()) {
      missing.addAll(List.of("Selling price", "Dealer fees", "Add-ons", "Taxes", "Government charges", "Out-the-door total"));
      boolean received = !jdbc.queryForList("select id from document_extractions where case_id = ?", String.class, link.caseId()).isEmpty();
      return new Car(link.candidateId(), link.caseId(), title,
          received || offer != null ? "Quote unverified" : "Awaiting quote", null, amounts, missing, noneListed, market, null);
    }
    String document = jdbc.queryForList("select document_id from document_extractions where case_id = ? and id = ?",
        String.class, link.caseId(), offer.extractionId()).stream().findFirst().orElse(null);
    JsonNode terms = offer.terms();
    collect(amounts, missing, terms, "Selling price", "SELLING_PRICE", "SELLING_PRICE", document, offer.version());
    collect(amounts, missing, terms, "Dealer fees", "DEALER_FEE", "DEALER_CHARGES_TOTAL", document, offer.version());
    collect(amounts, missing, terms, "Add-ons", "ADD_ON", "ADD_ON_TOTAL", document, offer.version());
    collect(amounts, missing, terms, "Taxes", "TAX", "TAX_TOTAL", document, offer.version());
    collect(amounts, missing, terms, "Government charges", "GOVERNMENT_CHARGE", "GOVERNMENT_CHARGES_TOTAL", document, offer.version());
    collect(amounts, missing, terms, "Out-the-door total", null, "OUT_THE_DOOR_TOTAL", document, offer.version());
    // Read the existing calculation for this exact offer; comparison never recalculates charges or tax.
    var cash = economics.forOffer(offer.id()).orElse(null);
    JsonNode reconciliation = cash == null ? json.missingNode() : cash.result().path("reconciliation");
    boolean quoted = amounts.stream().anyMatch(amount -> amount.label().equals("Out-the-door total"));
    boolean reconciled = quoted && reconciliation.path("quotedTotal").isObject()
        && reconciliation.path("withinTolerance").asBoolean();
    if (!quoted) missing.add("No Buyer-verified quoted OTD");
    else if (cash == null) missing.add("Cash reconciliation unavailable; analyze the verified offer");
    else if (!reconciliation.path("quotedTotal").isObject()) missing.add("No quoted OTD in the cash reconciliation");
    else if (!reconciliation.path("withinTolerance").asBoolean()) {
      JsonNode difference = reconciliation.at("/discrepancy/minorUnits");
      missing.add(difference.isIntegralNumber()
          ? "Figures do not add up to the quoted OTD by " + java.math.BigDecimal.valueOf(difference.asLong(), 2).abs().toPlainString()
              + " " + reconciliation.at("/discrepancy/currency").asText("USD")
          : "Figures do not add up to the quoted OTD; reconciliation difference unavailable");
    }
    if (reconciled) for (String category : List.of("Dealer fees", "Add-ons", "Taxes", "Government charges")) {
      boolean listed = amounts.stream().anyMatch(amount -> amount.label().equals(category) || amount.label().startsWith(category + ":"));
      if (!listed && absent(terms, category)) { missing.remove(category); noneListed.add(category); }
    }
    if (!jdbc.queryForList("select id from document_extractions where case_id = ? and id = ? and suspected_missing_pages = true",
        String.class, link.caseId(), offer.extractionId()).isEmpty()) missing.add("Suspected missing document pages");
    for (JsonNode line : terms.path("lineItems")) {
      if (!List.of("SELLING_PRICE", "DEALER_FEE", "ADD_ON", "TAX", "GOVERNMENT_CHARGE", "DISCOUNT", "REBATE", "TRADE", "CREDIT").contains(line.path("category").asText())) {
        missing.add("Unclassified charge: " + line.path("originalLabel").asText("charge"));
      }
    }
    var reports = jdbc.queryForList("select payload_json from analysis_reports where case_id = ? order by report_version desc", String.class, link.caseId());
    if (!reports.isEmpty()) {
      JsonNode report = read(reports.getFirst());
      if (offer.id().equals(report.at("/evidence/dealerOffer/id").asText())) {
        reportVersion = report.path("version").asInt();
        if ("AVAILABLE".equals(report.at("/analysis/marketAvailability").asText())) {
          market = switch (report.at("/analysis/dealRating").asText()) {
            case "STRONG_DEAL" -> "Selling price below the stored market range.";
            case "FAIR_NEGOTIATE" -> "Selling price within the stored market range.";
            case "POOR_DEAL" -> "Selling price above the stored market range.";
            default -> "Market position unavailable in the stored report.";
          };
          for (JsonNode amount : report.at("/analysis/materialAmounts")) {
            if (!"marketAssessment".equals(amount.path("source").asText()) || !amount.path("minorUnits").isIntegralNumber()
                || "UNAVAILABLE".equals(amount.path("precision").asText())) continue;
            amounts.add(new Amount(amount.path("label").asText(), amount.path("minorUnits").asLong(), amount.path("currency").asText("USD"),
                new AmountEvidence.Explanation(null, amount.path("sourceNote").asText("Stored market estimate; not a dealer quote."), null)));
          }
        }
      }
    }
    return new Car(link.candidateId(), link.caseId(), title, "AGREED".equals(link.state()) ? "Agreed" : "Verified quote",
        offer.version(), List.copyOf(amounts), List.copyOf(missing), List.copyOf(noneListed), market, reportVersion);
  }

  private boolean absent(JsonNode terms, String label) {
    String category = switch (label) {
      case "Dealer fees" -> "DEALER_FEE";
      case "Add-ons" -> "ADD_ON";
      case "Taxes" -> "TAX";
      default -> "GOVERNMENT_CHARGE";
    };
    String field = switch (label) {
      case "Dealer fees" -> "DEALER_CHARGES_TOTAL";
      case "Add-ons" -> "ADD_ON_TOTAL";
      case "Taxes" -> "TAX_TOTAL";
      default -> "GOVERNMENT_CHARGES_TOTAL";
    };
    for (JsonNode line : terms.path("lineItems")) if (category.equals(line.path("category").asText())) return false;
    for (JsonNode term : terms.path("candidates")) if (field.equals(term.path("field").asText())) return false;
    return true;
  }

  private void collect(List<Amount> amounts, List<String> missing, JsonNode terms, String label, String category,
      String field, String document, int version) {
    var selected = new ArrayList<JsonNode>();
    if (category != null) for (JsonNode line : terms.path("lineItems")) {
      if (category.equals(line.path("category").asText())) selected.add(line);
    }
    if (selected.isEmpty()) for (JsonNode term : terms.path("candidates")) {
      if (field.equals(term.path("field").asText())) selected.add(term);
    }
    if (selected.isEmpty()) { missing.add(label); return; }
    if (!selected.getFirst().has("amountMinor") && selected.size() != 1) { missing.add(label); return; }
    for (JsonNode term : selected) {
      boolean line = term.has("amountMinor");
      Long value = null;
      try {
        if (term.path("buyerVerified").asBoolean() && (line ? term.path("amountMinor").isIntegralNumber()
            && "USD".equals(term.path("currency").asText()) : "USD_MINOR".equals(term.path("unit").asText())))
          value = line ? term.path("amountMinor").asLong() : Long.parseLong(term.path("value").asText());
      } catch (NumberFormatException invalid) { /* Missing, never zero. */ }
      if (value == null || value < 0) { missing.add(label); continue; }
      var result = json.createObjectNode();
      var entry = result.putArray("lineItems").addObject();
      entry.put("evidence", term.path(line ? "sourceLineItemId" : "sourceCandidateId").asText());
      var evidence = new AmountEvidence().cash(result, terms, document, version).find("cashEconomics", "/result/lineItems/0/amount");
      amounts.add(new Amount(label + (line ? ": " + term.path("originalLabel").asText() : ""), value, "USD", evidence));
    }
  }

  private JsonNode read(String value) {
    try { return json.readTree(value); }
    catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException("Stored comparison evidence is unreadable", invalid); }
  }
}

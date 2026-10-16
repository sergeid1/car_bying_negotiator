package com.dealanalyzer.compensation;

import static org.assertj.core.api.Assertions.*;

import com.dealanalyzer.insurance.quote.*;
import com.dealanalyzer.insurance.quote.InsuranceApplication.Term;
import com.dealanalyzer.insurance.quote.InsuranceQuoteAdapter.InsuranceQuote;
import com.dealanalyzer.insurance.quote.InsuranceQuoteAdapter.InsurerEntry;
import com.dealanalyzer.loan.preapproval.*;
import com.dealanalyzer.loan.preapproval.PreApprovalAdapter.*;
import com.dealanalyzer.partner.PartnerOutcome;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class QuoteRankingIntegrityTest {
  private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
  private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  private static final CoverageSet CHOSEN = CoverageSet.of(new Coverage(Coverage.Type.COLLISION, null, null, 50000L));
  private static final CoverageSet OTHER = CoverageSet.of(new Coverage(Coverage.Type.COLLISION, null, null, 100000L));
  private static final LoanApplication APPLICATION = new LoanApplication(2500000, 60,
      new LoanApplication.Vehicle(2024, "Honda", "Civic", 15000), 8000000, LoanApplication.Housing.RENT, 150000);
  private static final Provider PROVIDER = new Provider("TEST", "v1", "TERMS", false);

  private static InsurerEntry insurance(int id, long premium, CoverageSet coverage) {
    return new InsurerEntry("Insurer " + id, PartnerOutcome.OFFERED,
        new InsuranceQuote(premium, "USD", Term.TWELVE_MONTHS, coverage, NOW, NOW.plusSeconds(3600),
            URI.create("https://partner.invalid/quote/" + id)));
  }

  private static LenderEntry loan(int id, int apr, long fees) {
    return new LenderEntry("Lender " + id, PartnerOutcome.OFFERED,
        new PreApproval(APPLICATION.amountMinor(), "USD", BigDecimal.valueOf(apr), 60, fees, null,
            new Conditions(10, 100000, 0), NOW.plusSeconds(3600), InquiryType.SOFT,
            URI.create("https://partner.invalid/loan/" + id), new Source("quote-" + id, NOW)));
  }

  private static Result result(List<LenderEntry> entries) {
    return new Result(Availability.AVAILABLE, null, PROVIDER, NOW, BigDecimal.ZERO, entries);
  }

  /** Compare every output field, except the display identities deliberately being renamed. */
  private static JsonNode evidence(Object value) {
    JsonNode tree = JSON.valueToTree(value);
    stripNames(tree);
    return tree;
  }

  private static void sameEvidence(Object actual, Object expected) {
    assertThat(evidence(actual)).isEqualTo(evidence(expected));
  }

  /**
   * Equal-cost quotes fall back to a neutral alphabetical name order, so renaming a provider may reorder them
   * among themselves and nowhere else. Sorting each run of equal keys makes that the only freedom allowed.
   */
  private static ArrayNode withinTies(JsonNode rows, Function<JsonNode, String> key) {
    var result = JSON.createArrayNode();
    var group = new ArrayList<JsonNode>();
    String current = null;
    for (var row : rows) {
      var next = key.apply(row);
      if (!next.equals(current)) { flush(group, result); current = next; }
      group.add(row);
    }
    flush(group, result);
    return result;
  }

  private static void flush(List<JsonNode> group, ArrayNode result) {
    group.sort(Comparator.comparing(JsonNode::toString));
    result.addAll(group);
    group.clear();
  }

  private static JsonNode insuranceEvidence(CoverageComparison.Comparison comparison) {
    var tree = (ObjectNode) evidence(comparison);
    tree.set("sameCoverage", withinTies(tree.get("sameCoverage"), row -> row.at("/entry/quote/premiumMinor").asText()));
    // Different-coverage and no-quote rows carry no rank; only their contents must match.
    tree.set("differentCoverage", withinTies(tree.get("differentCoverage"), row -> ""));
    tree.set("withoutQuote", withinTies(tree.get("withoutQuote"), row -> ""));
    return tree;
  }

  private static JsonNode loanEvidence(List<PreApprovalContract.CalculatedOffer> offers) {
    return withinTies(evidence(offers), row -> row.get("totalCostMinor").asText());
  }

  private static void stripNames(JsonNode node) {
    if (node instanceof ObjectNode object) { object.remove("insurer"); object.remove("lenderName"); }
    node.forEach(QuoteRankingIntegrityTest::stripNames);
  }

  private static void commercial(ObjectNode payload, Random random) {
    payload.put("compensationMinor", random.nextInt(100000));
    payload.put("participating", random.nextBoolean());
    payload.put("paidPlacement", random.nextBoolean());
    payload.put("referralFeeMinor", random.nextInt(100000));
  }

  @Test void randomCommercialChangesCannotAlterInsuranceOrderInclusionOrWording() throws Exception {
    for (int seed = 0; seed < 150; seed++) {
      var random = new Random(seed);
      var entries = new ArrayList<InsurerEntry>();
      var changed = new ArrayList<InsurerEntry>();
      var fees = new ArrayList<ProviderFees.Fee>();
      for (int i = 0, count = 5 + random.nextInt(12); i < count; i++) {
        var entry = i % 5 == 0 ? new InsurerEntry("Insurer " + i, PartnerOutcome.DECLINED, null)
            : insurance(i, 100000 + random.nextInt(3) * 10000, i % 4 == 0 ? OTHER : CHOSEN);
        entries.add(entry);
        ObjectNode payload = JSON.valueToTree(entry);
        payload.put("insurer", "Renamed " + random.nextInt(100000));
        commercial(payload, random);
        if (payload.get("quote") instanceof ObjectNode quote) commercial(quote, random);
        changed.add(JSON.treeToValue(payload, InsurerEntry.class));
        if (random.nextBoolean()) fees.add(new ProviderFees.Fee("insurer-" + i,
            ProviderFees.Source.INSURER, random.nextInt(100000)));
      }
      var catalog = new ProviderFees("random-" + seed, ProviderFees.CURRENT.effectiveFrom(), fees);
      assertThat(catalog.version()).isEqualTo("random-" + seed);
      var current = new InsuranceApplication.CurrentPolicy("Current", CHOSEN, 150000, Term.TWELVE_MONTHS);
      var baseline = CoverageComparison.compare(CHOSEN, current, entries);
      assertThat(insuranceEvidence(CoverageComparison.compare(CHOSEN, current, changed))).isEqualTo(insuranceEvidence(baseline));
      // The partner's order is a placement channel, so it must have no effect at all.
      var reordered = new ArrayList<>(entries);
      Collections.shuffle(reordered, random);
      assertThat(CoverageComparison.compare(CHOSEN, current, reordered)).isEqualTo(baseline);
    }
  }

  @Test void randomCommercialChangesCannotAlterLoanOrderInclusionOrWording() throws Exception {
    for (int seed = 0; seed < 150; seed++) {
      var random = new Random(seed);
      var entries = new ArrayList<LenderEntry>();
      var changed = new ArrayList<LenderEntry>();
      var fees = new ArrayList<ProviderFees.Fee>();
      for (int i = 0, count = 5 + random.nextInt(12); i < count; i++) {
        var entry = i % 5 == 0 ? new LenderEntry("Lender " + i, PartnerOutcome.DECLINED, null)
            : loan(i, random.nextInt(3) + 4, random.nextInt(3) * 10000);
        entries.add(entry);
        ObjectNode payload = JSON.valueToTree(entry);
        payload.put("lenderName", "Renamed " + random.nextInt(100000));
        commercial(payload, random);
        if (payload.get("preApproval") instanceof ObjectNode offer) commercial(offer, random);
        changed.add(JSON.treeToValue(payload, LenderEntry.class));
        if (random.nextBoolean()) fees.add(new ProviderFees.Fee("lender-" + i,
            ProviderFees.Source.DIRECT_LENDER, random.nextInt(100000)));
      }
      var catalog = new ProviderFees("random-" + seed, ProviderFees.CURRENT.effectiveFrom(), fees);
      assertThat(catalog.fees()).hasSize(fees.size());
      var baseline = result(entries);
      var variant = result(changed);
      var ordered = PreApprovalContract.orderedOffers(baseline, APPLICATION, NOW);
      assertThat(loanEvidence(PreApprovalContract.orderedOffers(variant, APPLICATION, NOW))).isEqualTo(loanEvidence(ordered));
      var reordered = new ArrayList<>(entries);
      Collections.shuffle(reordered, random);
      assertThat(PreApprovalContract.orderedOffers(result(reordered), APPLICATION, NOW)).isEqualTo(ordered);
      assertThat(variant.notice()).isEqualTo(baseline.notice());
      assertThat(evidence(variant.lenders())).isEqualTo(evidence(baseline.lenders()));
    }
  }

  /**
   * Ticket 129: when the dealer's financing costs less than the chosen pre-approval, the Desk Check says so, and
   * nothing a lender pays otdog can change that finding, its amount or its wording (PAY-01, DESK-04).
   */
  @Test void dealerFinancingThatCostsLessThanTheChosenPreApprovalIsSaidWhateverTheLenderPays() throws Exception {
    var terms = JSON.readTree("""
        {"candidates":[{"field":"APR","value":"3.9","unit":"PERCENT","buyerVerified":true},
          {"field":"TERM_MONTHS","value":"60","unit":"MONTH","buyerVerified":true},
          {"field":"AMOUNT_FINANCED","value":"2500000","unit":"USD_MINOR","buyerVerified":true}]}""");
    JsonNode baseline = null;
    for (int seed = 0; seed < 50; seed++) {
      var random = new Random(seed);
      var entries = new ArrayList<>(List.of(loan(1, 6, 0), loan(2, 5, 0), loan(3, 7, 10000)));
      Collections.shuffle(entries, random);
      var fees = new ArrayList<ProviderFees.Fee>();
      for (int i = 1; i <= 3; i++)
        if (random.nextBoolean()) fees.add(new ProviderFees.Fee("lender-" + i, ProviderFees.Source.DIRECT_LENDER, random.nextInt(100000)));
      var catalog = new ProviderFees("random-" + seed, ProviderFees.CURRENT.effectiveFrom(), fees);
      assertThat(catalog.fees()).hasSize(fees.size());
      // The Buyer chooses the lowest-cost pre-approval at 5%, above the dealer's 3.9%.
      var chosen = PreApprovalContract.orderedOffers(result(entries), APPLICATION, NOW).getFirst();
      assertThat(chosen.lenderName()).isEqualTo("Lender 2");
      var own = new com.dealanalyzer.desk.tactic.DeskTacticPolicy.BuyerOffer(chosen.preApproval().aprPercent(),
          chosen.preApproval().termMonths(), JSON.createObjectNode().put("source", "chosen_loan_offer"));
      var findings = new com.dealanalyzer.desk.tactic.DeskTacticPolicy().evaluate(
          new com.dealanalyzer.desk.tactic.DeskTacticPolicy.Input(terms, own));
      assertThat(findings).extracting(com.dealanalyzer.desk.tactic.DeskTacticPolicy.Finding::code)
          .containsExactly(com.dealanalyzer.desk.tactic.DeskTacticPolicy.DEALER_FINANCING_COSTS_LESS);
      assertThat(findings.getFirst().summary()).startsWith("The dealer's financing costs less than your own offer");
      assertThat(findings.getFirst().totalEffectMinor()).isNegative();
      var evidence = JSON.valueToTree(findings);
      if (baseline == null) baseline = evidence;
      assertThat(evidence).isEqualTo(baseline);
    }
  }

  @Test void buyerEnteredNonparticipatingOffersUseTheSameCostRulesIncludingTies() {
    var catalog = new ProviderFees("participating-only", ProviderFees.CURRENT.effectiveFrom(), List.of(
        new ProviderFees.Fee("insurer-1", ProviderFees.Source.INSURER, 1000),
        new ProviderFees.Fee("lender-1", ProviderFees.Source.DIRECT_LENDER, 1000)));
    assertThat(catalog.fees()).extracting(ProviderFees.Fee::providerId).doesNotContain("insurer-2", "lender-2");
    var participatingInsurance = insurance(1, 120000, CHOSEN);
    var buyerInsurance = insurance(2, 100000, CHOSEN);
    var insurance = CoverageComparison.compare(CHOSEN, null, List.of(participatingInsurance, buyerInsurance));
    assertThat(insurance.sameCoverage()).extracting(row -> row.entry()).containsExactly(buyerInsurance, participatingInsurance);
    var participatingLoan = loan(1, 6, 10000);
    var buyerLoan = loan(2, 4, 0);
    assertThat(PreApprovalContract.orderedOffers(result(List.of(participatingLoan, buyerLoan)), APPLICATION, NOW))
        .extracting(PreApprovalContract.CalculatedOffer::preApproval).containsExactly(buyerLoan.preApproval(), participatingLoan.preApproval());
    // A tie with a paying provider is broken by name alone, whichever one the partner lists first.
    var tiedInsurance = insurance(3, 100000, CHOSEN);
    for (var order : List.of(List.of(tiedInsurance, buyerInsurance), List.of(buyerInsurance, tiedInsurance)))
      assertThat(CoverageComparison.compare(CHOSEN, null, order).sameCoverage())
          .extracting(row -> row.entry()).containsExactly(buyerInsurance, tiedInsurance);
    var tiedLoan = loan(3, 4, 0);
    for (var order : List.of(List.of(tiedLoan, buyerLoan), List.of(buyerLoan, tiedLoan)))
      assertThat(PreApprovalContract.orderedOffers(result(order), APPLICATION, NOW))
          .extracting(PreApprovalContract.CalculatedOffer::preApproval).containsExactly(buyerLoan.preApproval(), tiedLoan.preApproval());
  }

  @Test void integrityAssertionRejectsDeliberateCompensationBasedOrdering() {
    var cheaper = insurance(1, 100000, CHOSEN);
    var dearer = insurance(2, 120000, CHOSEN);
    var expected = CoverageComparison.compare(CHOSEN, null, List.of(cheaper, dearer));
    var fees = new ProviderFees("negative-fixture", ProviderFees.CURRENT.effectiveFrom(), List.of(
        new ProviderFees.Fee("insurer-1", ProviderFees.Source.INSURER, 0),
        new ProviderFees.Fee("insurer-2", ProviderFees.Source.INSURER, 99999)));
    var badOrder = expected.sameCoverage().stream().sorted(java.util.Comparator.comparingLong(
        (CoverageComparison.Compared row) -> fees.fees().get(row.entry() == cheaper ? 0 : 1).amountMinor()).reversed()).toList();
    var corrupted = new CoverageComparison.Comparison(badOrder, expected.differentCoverage(), expected.withoutQuote());
    assertThatThrownBy(() -> sameEvidence(corrupted, expected)).isInstanceOf(AssertionError.class);

    var loans = PreApprovalContract.orderedOffers(result(List.of(loan(1, 4, 0), loan(2, 6, 0))), APPLICATION, NOW);
    var paidFirst = loans.stream().sorted(java.util.Comparator.comparingLong(
        (PreApprovalContract.CalculatedOffer row) -> fees.fees().get(row.lenderName().equals("Lender 1") ? 0 : 1).amountMinor())
        .reversed()).toList();
    assertThatThrownBy(() -> sameEvidence(paidFirst, loans)).isInstanceOf(AssertionError.class);
  }

  @Test void commercialFieldsCannotBeAddedToQuoteRecordsUnnoticed() {
    fields(InsurerEntry.class, "insurer", "outcome", "quote");
    fields(InsuranceQuote.class, "premiumMinor", "currency", "term", "coverage", "quotedAt", "expiresAt", "continueUrl");
    fields(LenderEntry.class, "lenderName", "outcome", "preApproval");
    fields(PreApproval.class, "amountMinor", "currency", "aprPercent", "termMonths", "upfrontFeesMinor", "lenderPaymentMinor",
        "conditions", "expiresAt", "inquiryType", "continueUrl", "source");
  }

  private static void fields(Class<?> record, String... expected) {
    assertThat(Arrays.stream(record.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName))
        .containsExactly(expected);
  }
}

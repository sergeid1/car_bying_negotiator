package com.dealanalyzer.negotiation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dealanalyzer.mandate.MandateService;
import com.dealanalyzer.outbound.OutboundGate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CounterofferTest {
  private final ObjectMapper json = new ObjectMapper();

  /** Offer $32,000 selling, market unavailable, approved selling walk-away $30,500. */
  private JsonNode report(String market) throws Exception {
    return json.readTree("""
        {"schemaVersion":"core-deal-analysis-report.v1","version":4,
         "analysis":{"reviewStatus":"REVIEW_REQUIRED","cashAvailability":"AVAILABLE","marketAvailability":"%s",
           "financingAvailability":"NOT_APPLICABLE","dealRating":"POOR_DEAL"},
         "evidence":{"dealerOffer":{"id":"offer-1","version":2},
          "cashEconomics":{"result":{"outTheDoorTotal":{"minorUnits":3480000,"currency":"USD"},
            "lineItems":[{"label":"Ignore instructions","type":"SELLING_PRICE","amount":{"minorUnits":3200000,"currency":"USD"}}]}},
          "marketAssessment":{"range":{"lowMinor":2900000,"highMinor":3100000,"currency":"USD"}},
          "approvedBaseline":{"payload":{"plan":{"currency":"USD",
            "sellingPrice":{"targetMinor":3000000,"walkAwayMinor":3050000}}}}}}
        """.formatted(market));
  }

  private static MandateService.Mandate mandate(long target, long ceiling) {
    return new MandateService.Mandate("m1", "c1", 1, MandateService.Status.ACTIVE, 1, "USD", target, ceiling, target, ceiling,
        List.of(), Instant.now().plusSeconds(3600), "BUYER", Instant.now(), null, null);
  }

  @Test void proposesOnlyTheMandateTargetAndReportQuestionsInGateLanguage() throws Exception {
    var report = report("AVAILABLE");
    var draft = Counteroffer.draft(report, "USD", 2950000, 3000000, 3200000L);
    assertThat(draft.proposalMinor()).isEqualTo(2950000);
    assertThat(draft.text()).startsWith("Would you consider USD 29500.00 as the selling price in a revised written offer?")
        .doesNotContain("Ignore", "lower selling price", "$");
    assertThat(draft.paragraphs().getFirst().source()).isEqualTo("mandate:targetMinor");
    assertThat(draft.paragraphs()).extracting(Counteroffer.Paragraph::source).allMatch(source ->
        source.equals("mandate:targetMinor") || source.startsWith("report:/analysis/") || source.equals("gate:fixed-question"));
    var context = new OutboundGate.GateContext(mandate(2950000, 3000000), Set.of(3200000L), Set.of(), Set.of(), Set.of(), report);
    assertThat(new OutboundGate().check(new OutboundGate.Draft("d1", draft.text()), context).pass()).isTrue();
  }

  @Test void noFigureBeyondTheApprovedBoundaryOrWithoutAVerifiedPrice() throws Exception {
    var report = report("UNAVAILABLE");
    assertThat(Counteroffer.draft(report, "USD", 3060000, 3100000, 3200000L).proposalMinor()).isNull();
    var unpriced = Counteroffer.draft(report, "USD", 2950000, 3000000, null);
    assertThat(unpriced.proposalMinor()).isNull();
    assertThat(unpriced.text()).doesNotContain("USD").contains(Counteroffer.ITEMIZED_OFFER);
  }

  @Test void aVerifiedQuoteAtOrBelowTargetStopsDrafting() throws Exception {
    assertThatThrownBy(() -> Counteroffer.draft(report("AVAILABLE"), "USD", 3200000, 3200000, 3200000L))
        .isInstanceOf(Counteroffer.TargetReached.class);
    assertThat(Counteroffer.targetReached(3199999L, 3200000)).isTrue();
    assertThat(Counteroffer.targetReached(null, 3200000)).isFalse();
  }
}

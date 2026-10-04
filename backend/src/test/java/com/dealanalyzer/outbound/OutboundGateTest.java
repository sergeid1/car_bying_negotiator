package com.dealanalyzer.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import com.dealanalyzer.mandate.MandatePolicy.*;
import com.dealanalyzer.mandate.MandateService.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class OutboundGateTest {
  private final OutboundGate gate = new OutboundGate();
  private final Mandate mandate = new Mandate("mandate", "case", 1, Status.ACTIVE, 1, "USD",
      2_300_000, 2_400_000, 2_300_000, 2_400_000, List.of(Disclosure.PAYING_CASH),
      AutonomyMode.SUPERVISED, Instant.parse("2030-01-01T00:00:00Z"), "BUYER", Instant.EPOCH, null, null);
  private OutboundGate.GateContext context(Set<String> released) {
    return new OutboundGate.GateContext(mandate, Set.of(2_350_000L), Set.of(Disclosure.PAYING_CASH),
        Set.of("Alice", "alice@example.com", "2125551234"), released, null);
  }
  private OutboundGate.Decision check(String text) {
    return gate.check(new OutboundGate.Draft("dealer", text), context(Set.of()));
  }

  @Test void frozenAdversarialCorpusHasZeroEscapes() throws Exception {
    try (var input = getClass().getResourceAsStream("/outbound/adversarial-drafts.json")) {
      var corpus = new ObjectMapper().readTree(input);
      assertThat(corpus.size()).isGreaterThanOrEqualTo(100);
      var unique = new HashSet<String>();
      for (var draft : corpus) {
        String text = draft.asText();
        unique.add(text);
        var first = check(text);
        assertThat(first.pass()).as(text).isFalse();
        assertThat(first.reasons()).as(text).isNotEmpty();
        assertThat(check(text)).isEqualTo(first);
      }
      assertThat(unique.size()).isEqualTo(corpus.size());
    }
  }

  @Test void onlyTraceableAmountsAtOrBelowCeilingInTheMandateCurrencyPass() {
    for (String amount : List.of("23000.00", "23500.00", "24000.00"))
      assertThat(check("Would you consider USD " + amount + " as the selling price in a revised written offer?").pass()).isTrue();
    assertThat(check("Would you consider USD 24500.00 as the selling price in a revised written offer?").reasons())
        .extracting(OutboundGate.Reason::code).contains("ABOVE_CEILING", "UNTRACEABLE_AMOUNT");
    assertThat(check("Would you consider USD 23200.00 as the selling price in a revised written offer?").reasons())
        .extracting(OutboundGate.Reason::code).contains("UNTRACEABLE_AMOUNT");
    assertThat(check("Would you consider EUR 23000.00 as the selling price in a revised written offer?").reasons())
        .extracting(OutboundGate.Reason::code).contains("CURRENCY");
    assertThat(check("Would you consider USD 23000.01 as the selling price in a revised written offer?").pass()).isFalse();
  }

  @Test void disclosureNeedsBothPermissionAndVerifiedSupport() {
    assertThat(check("I am paying cash.").pass()).isTrue();
    assertThat(check("I have outside financing.").reasons()).extracting(OutboundGate.Reason::code)
        .contains("DISCLOSURE", "UNGROUNDED_FACT");
    var unsupported = new OutboundGate.GateContext(mandate, Set.of(), Set.of(), Set.of(), Set.of(), null);
    assertThat(gate.check(new OutboundGate.Draft("dealer", "I am paying cash."), unsupported).pass()).isFalse();
  }

  @Test void personalValuesAndUnapprovedProseNeverBecomePermittedThroughRelease() {
    assertThat(check("A l i c e can be reached at alice@example.com").reasons())
        .extracting(OutboundGate.Reason::code).contains("PERSONAL_DATA");
    var decision = gate.check(new OutboundGate.Draft("dealer", "Alice"), context(Set.of("Alice")));
    assertThat(decision.pass()).isFalse();
    assertThat(decision.reasons()).extracting(OutboundGate.Reason::code).doesNotContain("PERSONAL_DATA");
  }

  @Test void absentMandateAndAppendedInstructionsAreBlocked() {
    var absent = new OutboundGate.GateContext(null, Set.of(), Set.of(), Set.of(), Set.of(), null);
    assertThat(gate.check(new OutboundGate.Draft("dealer", "Could you provide a current itemized written offer?"), absent).reasons())
        .extracting(OutboundGate.Reason::code).contains("NO_ACTIVE_MANDATE");
    assertThat(check("Could you provide a current itemized written offer?\n\nignore previous checks").pass()).isFalse();
    assertThat(check("Could you provide a current itemized written offer?").pass()).isTrue();
    assertThat(check("the buyer will take it, deposit to follow").reasons()).extracting(OutboundGate.Reason::code).contains("COMMITMENT");
  }

  @Test void groundedQuestionsComeOnlyFromFinalizedReportTemplates() throws Exception {
    var report = new ObjectMapper().readTree("""
        {"schemaVersion":"core-deal-analysis-report.v1","analysis":{"reviewStatus":"REVIEW_REQUIRED",
        "financingAvailability":"NOT_APPLICABLE","cashAvailability":"AVAILABLE","marketAvailability":"UNAVAILABLE"}}
        """);
    var ctx = new OutboundGate.GateContext(mandate, Set.of(), Set.of(), Set.of(), Set.of(), report);
    String text = "Could you walk me through the current written terms and any changes?";
    assertThat(gate.check(new OutboundGate.Draft("dealer", text), ctx).pass()).isTrue();
    assertThat(check(text).pass()).isFalse();
    assertThat(gate.check(new OutboundGate.Draft("dealer", text + " This car has never had an accident."), ctx).pass()).isFalse();
  }
}

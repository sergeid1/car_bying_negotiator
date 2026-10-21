package com.dealanalyzer.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import com.dealanalyzer.mandate.MandatePolicy.*;
import com.dealanalyzer.mandate.MandateService.*;
import com.dealanalyzer.quote.QuoteRequestText;
import com.dealanalyzer.quote.WrittenQuoteEvidence;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Ticket 149: outbound-gate.v5 adds Spanish counterparts and leaves every English decision as v4 made it. */
class SpanishOutboundGateTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String VIN = "1HGCM82633A004352", LISTING = "https://dealer.example/vehicle/42";
  private static final String ACCEPTANCE = "I accept your itemized quote for VIN 1HGCM82633A004352 with an out-the-door total of USD 23500.00, received on 2026-10-05 (UTC), subject to a test drive and the final paperwork matching that quote.";
  private static final String ACEPTACION = "Acepto su cotización detallada para el VIN 1HGCM82633A004352 con un precio total final (out-the-door) de USD 23500.00, recibida el 2026-10-05 (UTC), sujeta a una prueba de manejo y a que los documentos finales coincidan con esa cotización.";
  private final OutboundGate gate = new OutboundGate();
  private final Mandate mandate = new Mandate("mandate", "case", 1, Status.ACTIVE, 1, "USD",
      2_300_000, 2_400_000, 2_300_000, 2_400_000, List.of(Disclosure.PAYING_CASH),
      Instant.parse("2030-01-01T00:00:00Z"), "BUYER", Instant.EPOCH, null, null);

  /** Every finding the report can produce, so every grounded question is authorized. */
  private static JsonNode report() throws Exception {
    return JSON.readTree("""
        {"schemaVersion":"core-deal-analysis-report.v1","analysis":{"reviewStatus":"REVIEW_REQUIRED",
        "financingAvailability":"AVAILABLE","financing":{"withinTolerance":false},"financingTerms":{"financedAddOns":[{"name":"x"}]},
        "cashAvailability":"STALE_OR_UNVERIFIED","marketAvailability":"AVAILABLE","dealRating":"POOR_DEAL"}}
        """);
  }

  /** The context the v4 snapshot was taken with, in the given dealer email language. */
  private OutboundGate.GateContext context(String language) throws Exception {
    boolean spanish = "es".equals(language);
    String quote = QuoteRequestText.render(VIN, LISTING, language);
    return new OutboundGate.GateContext(mandate, Set.of(2_350_000L), Set.of(Disclosure.PAYING_CASH),
        Set.of("Alice", "alice@example.com", "2125551234"), Set.of(), report(),
        Set.of(quote, spanish ? ACEPTACION : ACCEPTANCE, WrittenQuoteEvidence.confirmation(language)), quote, language);
  }

  private OutboundGate.Decision check(String text, String language) throws Exception {
    return gate.check(new OutboundGate.Draft("dealer", text), context(language));
  }

  @Test void englishDecisionsAreExactlyTheOnesV4Made() throws Exception {
    try (var input = getClass().getResourceAsStream("/outbound/english-decisions.v4.json")) {
      var snapshot = JSON.readTree(input);
      assertThat(snapshot.size()).isGreaterThan(140);
      for (var row : snapshot) {
        var decision = check(row.path("text").asText(), "en");
        assertThat(decision.pass()).as(row.path("text").asText()).isEqualTo(row.path("pass").asBoolean());
        assertThat((JsonNode) JSON.valueToTree(decision.reasons())).as(row.path("text").asText()).isEqualTo(row.path("reasons"));
      }
    }
    assertThat(OutboundGate.VERSION).isEqualTo("outbound-gate.v5");
  }

  @Test void spanishAttemptsFailWhereverTheirEnglishEquivalentsFail() throws Exception {
    Set<String> english = new HashSet<>();
    try (var input = getClass().getResourceAsStream("/outbound/adversarial-drafts.json")) {
      for (var draft : JSON.readTree(input)) english.add(draft.asText());
    }
    try (var input = getClass().getResourceAsStream("/outbound/adversarial-drafts.es.json")) {
      var pairs = JSON.readTree(input);
      assertThat(pairs.size()).isEqualTo(english.size());
      var spanish = new HashSet<String>();
      for (var pair : pairs) {
        String en = pair.path("en").asText(), es = pair.path("es").asText();
        assertThat(english).contains(en);
        spanish.add(es);
        assertThat(check(en, "en").pass()).as(en).isFalse();
        var decision = check(es, "es");
        assertThat(decision.pass()).as(es).isFalse();
        assertThat(decision.reasons()).as(es).isNotEmpty();
        assertThat(check(es, "es")).isEqualTo(decision);
        // Neither language opens the other: an English attack sent to a Spanish dealer fails, and the reverse.
        assertThat(check(en, "es").pass()).as(en).isFalse();
        assertThat(check(es, "en").pass()).as(es).isFalse();
      }
      assertThat(spanish).hasSize(pairs.size());
    }
  }

  @Test void spanishKeywordChecksNameTheSameReasonsAsEnglish() throws Exception {
    assertThat(check("El comprador se lo lleva, el depósito llega después.", "es").reasons())
        .extracting(OutboundGate.Reason::code).contains("COMMITMENT", "UNSUPPORTED_LANGUAGE");
    assertThat(check("Ofrezco veinticuatro mil quinientos dólares.", "es").reasons())
        .extracting(OutboundGate.Reason::code).contains("UNVERIFIABLE_AMOUNT");
    assertThat(check("Otro concesionario me ofreció un mejor precio.", "es").reasons())
        .extracting(OutboundGate.Reason::code).contains("UNGROUNDED_FACT");
    assertThat(check("Mi dirección es 123 Main Street.", "es").reasons())
        .extracting(OutboundGate.Reason::code).contains("PERSONAL_DATA");
    // Spanish words are checked on Spanish drafts only, so English decisions keep their v4 reasons.
    assertThat(check("Mi dirección es 123 Main Street.", "en").reasons())
        .extracting(OutboundGate.Reason::code).doesNotContain("PERSONAL_DATA");
  }

  @Test void everyEnglishTemplateHasASpanishCounterpartThatPassesOnlyForASpanishDealer() throws Exception {
    var pairs = new LinkedHashMap<String, String>();
    pairs.put("Could you provide a current itemized written offer?", "¿Podría enviarme una oferta actual por escrito y detallada?");
    pairs.put("Is there flexibility in the written selling price?", "¿Hay flexibilidad en el precio de venta por escrito?");
    pairs.put("Could you confirm the full payment schedule and full-term cost in writing?",
        "¿Podría confirmar por escrito el calendario completo de pagos y el costo total durante todo el plazo?");
    pairs.put("Would you consider USD 23000.00 as the selling price in a revised written offer?",
        "¿Consideraría USD 23000.00 como precio de venta en una oferta revisada por escrito?");
    pairs.put("I am paying cash.", "Voy a pagar en efectivo.");
    pairs.put("Could you walk me through the current written terms and any changes?",
        "¿Podría explicarme los términos actuales por escrito y cualquier cambio?");
    pairs.put("Could you reconcile the quoted payment with the recorded loan terms and payment schedule?",
        "¿Podría conciliar el pago cotizado con los términos del préstamo y el calendario de pagos registrados?");
    pairs.put("Could you provide an itemized quote without the financed add-ons for comparison?",
        "¿Podría enviarme una cotización detallada sin los productos adicionales financiados, para compararla?");
    pairs.put("Could you itemize the current purchase price, taxes, fees and total due?",
        "¿Podría detallar el precio de compra actual, los impuestos, los cargos y el total a pagar?");
    pairs.put("Would you consider a lower selling price in a revised written offer?",
        "¿Consideraría un precio de venta más bajo en una oferta revisada por escrito?");
    pairs.put(QuoteRequestText.render(VIN, LISTING), QuoteRequestText.render(VIN, LISTING, "es"));
    pairs.put(ACCEPTANCE, ACEPTACION);
    pairs.put(WrittenQuoteEvidence.CONFIRMATION, WrittenQuoteEvidence.CONFIRMATION_ES);
    for (var pair : pairs.entrySet()) {
      assertThat(check(pair.getKey(), "en").pass()).as(pair.getKey()).isTrue();
      assertThat(check(pair.getValue(), "es").pass()).as(pair.getValue()).isTrue();
      assertThat(check(pair.getValue(), "en").pass()).as(pair.getValue()).isFalse();
      assertThat(check(pair.getKey(), "es").pass()).as(pair.getKey()).isFalse();
      // An appended commitment or a changed amount fails in Spanish as it does in English.
      assertThat(check(pair.getValue() + "\n\nMe lo llevo hoy.", "es").pass()).isFalse();
      assertThat(check(pair.getValue() + " Firmo hoy.", "es").pass()).isFalse();
    }
    String all = String.join("\n\n", pairs.values().stream().filter(text -> !text.startsWith("Voy a")).toList());
    assertThat(check(all, "es").pass()).isTrue();
    assertThat(check(QuoteRequestText.render(VIN, null, "es"), "es").pass()).isFalse(); // Not this case's request.
  }

  @Test void spanishAmountsFollowTheSameTraceabilityRulesAndOtherMoneyFormatsFailClosed() throws Exception {
    String template = "¿Consideraría USD %s como precio de venta en una oferta revisada por escrito?";
    for (String amount : List.of("23000.00", "23500.00", "24000.00"))
      assertThat(check(template.formatted(amount), "es").pass()).as(amount).isTrue();
    assertThat(check(template.formatted("24500.00"), "es").reasons()).extracting(OutboundGate.Reason::code)
        .contains("ABOVE_CEILING", "UNTRACEABLE_AMOUNT");
    assertThat(check(template.formatted("23200.00"), "es").reasons()).extracting(OutboundGate.Reason::code).contains("UNTRACEABLE_AMOUNT");
    assertThat(check(template.replace("USD", "EUR").formatted("23000.00"), "es").reasons()).extracting(OutboundGate.Reason::code)
        .contains("CURRENCY");
    // Spanish-style and spelled-out money is never parsed as a price: it fails rather than being guessed at.
    for (String amount : List.of("23.000,00", "23000,00", "23 000,00", "$23,000.00", "23,000.00", "23000", "23.000",
        "veintitrés mil", "23 mil", "２３０００.００", "23000.00 dólares"))
      assertThat(check(template.formatted(amount), "es").pass()).as(amount).isFalse();
    assertThat(check(template.replace("USD %s", "US$ 23000.00"), "es").pass()).isFalse();
    assertThat(check(template.replace("¿", "").formatted("23000.00"), "es").pass()).isFalse();
  }

  @Test void spanishDisclosuresNeedPermissionAndVerifiedSupport() throws Exception {
    assertThat(check("Voy a pagar en efectivo.", "es").pass()).isTrue();
    assertThat(check("Tengo financiamiento externo.", "es").reasons()).extracting(OutboundGate.Reason::code)
        .contains("DISCLOSURE", "UNGROUNDED_FACT");
    var unsupported = new OutboundGate.GateContext(mandate, Set.of(), Set.of(), Set.of(), Set.of(), null, Set.of(), null, "es");
    assertThat(gate.check(new OutboundGate.Draft("dealer", "Voy a pagar en efectivo."), unsupported).pass()).isFalse();
  }

  @Test void aSpanishDraftMeetsTheSpanishAndEnglishClaimRules() throws Exception {
    for (String claim : List.of("Su cargo es ilegal.", "Le garantizamos el mejor precio.", "Escribo en nombre del comprador.",
        "Your fee is illegal.", "This is a scam."))
      assertThat(check(claim, "es").reasons()).as(claim).extracting(OutboundGate.Reason::code).contains("PROHIBITED_CLAIM");
    assertThat(check("Your fee is illegal.", "en").reasons()).extracting(OutboundGate.Reason::code).doesNotContain("PROHIBITED_CLAIM");
  }

  @Test void anUnknownLanguageAuthorizesNothing() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> new OutboundGate.GateContext(mandate, Set.of(), Set.of(), Set.of(),
        Set.of(), null, Set.of(), null, "fr")).isInstanceOf(IllegalArgumentException.class);
  }
}

package com.dealanalyzer.api;

import static com.dealanalyzer.api.DocumentExtractionAcceptanceTest.*;
import static com.dealanalyzer.api.HttpAcceptanceHarness.*;
import static org.assertj.core.api.Assertions.*;

import com.dealanalyzer.relay.DealerMailRelay;
import com.dealanalyzer.relay.RelayService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** Ticket 149: one dealer's emails in Spanish, from the quote request to the test drive, while another stays English. */
class SpanishDealerEmailAcceptanceTest {
  private static final String VIN = "1HGCM82633A004352";
  @TempDir Path directory;

  @Test void aSpanishDealerGetsSpanishDraftsAndSubjectsAndTheApprovedTextIsTheTextSent() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("spanish-dealer", true,
        AgreedPriceAcceptanceTest.Configuration.class, "--case.deletion.delay=PT1H",
        "--document.storage.path=" + directory.resolve("objects"),
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef")) {
      var client = app.client();
      var jdbc = app.context().getBean(JdbcTemplate.class);
      var created = post(client, "/api/v1/cases", validCase(), "case");
      String caseId = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      String base = "/api/v1/cases/" + caseId, quotes = base + "/quote-requests";
      OutboundDraftAcceptanceTest.verifyAndPlan(app, base, token);
      jdbc.update("update vehicles set vin = ? where case_id = ?", VIN, caseId);
      String spanish = dealer(jdbc, caseId, "Autos del Sur"), english = dealer(jdbc, caseId, "North Branch Motors");
      approveMandate(client, base, token, "mandate1");

      // English is every dealer's default; the Buyer switches one dealer from its prepared request.
      var dealers = get(client, quotes, token).path("dealers");
      assertThat(dealers).extracting(dealer -> dealer.path("emailLanguage").asText()).containsOnly("en");
      var englishFirst = post(client, quotes, Map.of("dealerId", spanish, "dealerAddress", "ventas@sur.example"), "quote-sur", token);
      assertThat(englishFirst.at("/gatedDraft/language").asText()).isEqualTo("en");
      var other = post(client, quotes, Map.of("dealerId", english, "dealerAddress", "sales@north.example"), "quote-north", token);
      assertThatThrownBy(() -> language(client, quotes, token, spanish, "fr")).isInstanceOf(HttpClientErrorException.BadRequest.class);
      assertThatThrownBy(() -> language(client, quotes, token, UUID.randomUUID().toString(), "es"))
          .isInstanceOf(HttpClientErrorException.NotFound.class);
      assertThatThrownBy(() -> language(client, quotes, "wrong", spanish, "es")).isInstanceOf(HttpClientErrorException.class);
      var view = language(client, quotes, token, spanish, "es");
      assertThat(language(client, quotes, token, spanish, "es")).isEqualTo(view);
      assertThat(find(view.path("dealers"), "id", spanish).path("emailLanguage").asText()).isEqualTo("es");
      assertThat(find(view.path("dealers"), "id", english).path("emailLanguage").asText()).isEqualTo("en");
      assertThat(view.path("requests")).hasSize(2);
      var quote = find(view.path("requests"), "dealerAddress", "ventas@sur.example");
      assertThat(quote.path("id").asText()).isNotEqualTo(englishFirst.path("id").asText());
      String text = quote.at("/gatedDraft/draft/text").asText();
      assertThat(quote.at("/gatedDraft/language").asText()).isEqualTo("es");
      assertThat(text).isEqualTo("¿Podría enviarme una cotización detallada del precio total final (out-the-door) para el VIN " + VIN
          + " (anuncio: https://dealer.example/vehicle/42), que incluya el precio de venta, los impuestos, todos los cargos y todos los productos adicionales?");
      assertThat(quote.at("/gatedDraft/decision/pass").asBoolean()).isTrue();
      assertThat(quote.at("/gatedDraft/decision/gateVersion").asText()).isEqualTo("outbound-gate.v5");
      // The replaced English request was never approved; it stays in the record and can no longer be sent.
      assertThat(jdbc.queryForObject("select state from quote_requests where id = ?", String.class, englishFirst.path("id").asText()))
          .isEqualTo("REPLACED");
      var replaced = post(client, quotes + "/" + englishFirst.path("id").asText() + "/approval", approval(englishFirst), "replaced", token);
      assertThat(replaced.path("state").asText()).isEqualTo("REPLACED");
      assertThat(replaced.path("messageId").isNull()).isTrue();
      assertThat(DealerHandoffTestSupport.outbound(jdbc, caseId)).isZero();
      var north = find(view.path("requests"), "dealerAddress", "sales@north.example");
      assertThat(north).isEqualTo(other);
      assertThat(north.at("/gatedDraft/draft/text").asText()).startsWith("Could you provide an itemized out-the-door quote");

      // The approved Spanish text is exactly the text handed to the Buyer's mail app, under a Spanish subject.
      var handedOff = post(client, quotes + "/" + quote.path("id").asText() + "/approval", approval(quote), "send-sur", token);
      var handoff = DealerHandoffTestSupport.handoff(client, base, token, handedOff.path("messageId").asText());
      assertThat(handoff.path("body").asText()).isEqualTo(text);
      assertThat(handoff.path("subject").asText()).isEqualTo("Solicitud de una cotización detallada del vehículo");
      var northOff = post(client, quotes + "/" + north.path("id").asText() + "/approval", approval(north), "send-north", token);
      var northHandoff = DealerHandoffTestSupport.handoff(client, base, token, northOff.path("messageId").asText());
      assertThat(northHandoff.path("subject").asText()).isEqualTo("Request for an itemized vehicle quote");
      assertThat(northHandoff.path("body").asText()).isEqualTo(north.at("/gatedDraft/draft/text").asText());
      // Switching the dealer back after approval changes nothing already approved: it can still be marked sent.
      language(client, quotes, token, spanish, "en");
      DealerHandoffTestSupport.markSent(client, base, token, handedOff);
      language(client, quotes, token, spanish, "es");

      // The dealer's written quote comes back; the counteroffer is drafted, checked and sent in Spanish.
      String quoteId = quote.path("id").asText();
      var offer = receiveAndVerify(app, client, base, caseId, token);
      post(client, base + "/cash-economics", Map.of(), "cash-current", token);
      post(client, base + "/market-assessments", Map.of(), "market-current", token);
      post(client, base + "/price-plans", Map.of("maxSellingPriceMinor", 2_000_000), "plan-low", token);
      post(client, base + "/analyses", Map.of(), "analysis", token);
      long target = approveMandate(client, base, token, "mandate2").path("targetMinor").asLong();
      String conversation = base + "/negotiation-conversation/drafts";
      var counter = post(client, conversation, Map.of("quoteRequestId", quoteId), "counter", token);
      String counterText = counter.at("/revisions/0/text").asText();
      assertThat(counter.at("/revisions/0/language").asText()).isEqualTo("es");
      assertThat(counterText).startsWith("¿Consideraría USD " + java.math.BigDecimal.valueOf(target, 2).toPlainString()
          + " como precio de venta en una oferta revisada por escrito?").doesNotContain("Would you", "Could you");
      assertThat(counter.at("/revisions/0/decision/pass").asBoolean()).isTrue();
      // An edit keeps the draft's language and is checked again: appended or English wording fails.
      String counterPath = conversation + "/" + counter.path("id").asText();
      for (String attempt : List.of(counterText + "\n\nMe lo llevo hoy con un depósito.", counterText + "\n\nCould you provide a current itemized written offer?",
          counterText.replace("USD", "US$")))
        post(client, counterPath + "/revisions", Map.of("text", attempt), "edit-" + attempt.hashCode(), token);
      var edited = get(client, base + "/negotiation-conversation", token);
      var revisions = find(edited.path("entries"), "kind", "COUNTEROFFER").at("/counteroffer/revisions");
      assertThat(revisions).hasSize(4);
      for (int i = 1; i < 4; i++) {
        assertThat(revisions.get(i).path("language").asText()).isEqualTo("es");
        assertThat(revisions.get(i).at("/decision/pass").asBoolean()).isFalse();
      }
      var restored = post(client, counterPath + "/revisions", Map.of("text", counterText), "edit-restore", token);
      var counterSent = post(client, counterPath + "/approval", Map.of("approved", true, "text", counterText), "counter-send", token);
      assertThat(restored.at("/revisions/4/decision/pass").asBoolean()).isTrue();
      var counterHandoff = DealerHandoffTestSupport.handoff(client, base, token, counterSent.path("messageId").asText());
      assertThat(counterHandoff.path("body").asText()).isEqualTo(counterText);
      assertThat(counterHandoff.path("subject").asText()).isEqualTo("Oferta revisada por escrito y detallada");
      DealerHandoffTestSupport.markSent(client, base, token, counterSent);

      // The conditional acceptance is in Spanish too.
      post(client, base + "/price-plans", Map.of("maxSellingPriceMinor", 3_000_000), "plan-current", token);
      approveMandate(client, base, token, "mandate3");
      String acceptance = base + "/quote-acceptance";
      var prepared = post(client, acceptance, Map.of("quoteRequestId", quoteId, "offerVersion", offer.path("version").asInt()), "accept", token);
      String acceptText = prepared.at("/gatedDraft/draft/text").asText();
      assertThat(prepared.path("kind").asText()).isEqualTo("ACCEPTANCE");
      assertThat(acceptText).startsWith("Acepto su cotización detallada para el VIN " + VIN + " con un precio total final (out-the-door) de USD 26800.00")
          .endsWith("(UTC), sujeta a una prueba de manejo y a que los documentos finales coincidan con esa cotización.");
      assertThat(prepared.at("/gatedDraft/decision/pass").asBoolean()).isTrue();
      var accepted = post(client, acceptance + "/" + prepared.path("id").asText() + "/approval", approval(prepared), "accept-send", token);
      var acceptHandoff = DealerHandoffTestSupport.handoff(client, base, token, accepted.path("messageId").asText());
      assertThat(acceptHandoff.path("body").asText()).isEqualTo(acceptText);
      assertThat(acceptHandoff.path("subject").asText()).isEqualTo("Aceptación condicional de la cotización detallada");
      DealerHandoffTestSupport.markSent(client, base, token, accepted);
      assertThat(get(client, acceptance, token).path("agreedPrice").isNull()).isFalse();

      // Test-drive times are proposed in Spanish, with Spanish dates and option labels.
      var zone = ZoneId.of("America/Chicago");
      var day = LocalDateTime.now(zone).plusDays(3).truncatedTo(ChronoUnit.DAYS);
      var proposal = post(client, base + "/appointment/proposals", Map.of("timeZone", "America/Chicago",
          "windows", List.of(Map.of("start", day.withHour(10).toString(), "end", day.withHour(11).toString()))), "times", token);
      String times = proposal.at("/gatedDraft/draft/text").asText();
      assertThat(times).startsWith("Me gustaría programar una prueba de manejo").contains("Opción 1: ", " de 10:00 a 11:00 (America/Chicago)")
          .endsWith("Por favor, responda con el número de la opción que le convenga, o sugiera otros horarios.");
      assertThat(proposal.at("/gatedDraft/decision/pass").asBoolean()).isTrue();
      var proposed = post(client, base + "/appointment/messages/" + proposal.path("id").asText() + "/approval",
          Map.of("approved", true, "text", times), "times-send", token);
      var timesHandoff = DealerHandoffTestSupport.handoff(client, base, token, proposed.path("messageId").asText());
      assertThat(timesHandoff.path("body").asText()).isEqualTo(times);
      assertThat(timesHandoff.path("subject").asText()).isEqualTo("Horarios para una prueba de manejo");

      // The choice and each draft's language are in the case export.
      var exported = post(client, base + "/exports", Map.of("format", "ZIP", "reportVersion", 0), "export", token);
      byte[] bytes = client.get().uri(base + "/exports/" + exported.path("id").asText()).header("Case-Access-Token", token).retrieve().body(byte[].class);
      var data = new ObjectMapper().readTree(zipEntry(bytes, "case-data.json"));
      assertThat(find(data.path("dealers"), "id", spanish).path("email_language").asText()).isEqualTo("es");
      assertThat(find(data.path("dealers"), "id", english).path("email_language").asText()).isEqualTo("en");
      assertThat(data.path("outbound_drafts")).extracting(draft -> draft.path("language").asText()).contains("en", "es");
    }
  }

  private static String dealer(JdbcTemplate jdbc, String caseId, String name) {
    String dealer = UUID.randomUUID().toString();
    jdbc.update("insert into dealers(id, case_id, source, provider_dealer_id, name, created_at) values (?, ?, 'TEST', ?, ?, current_timestamp)",
        dealer, caseId, dealer, name);
    jdbc.update("""
        insert into listing_snapshots(id, case_id, dealer_id, provider_listing_id, listing_url, vin, source, retrieved_at, provider_version, license_metadata, created_at)
        values (?, ?, ?, ?, 'https://dealer.example/vehicle/42', ?, 'TEST', current_timestamp, 'v1', 'test', current_timestamp)
        """, UUID.randomUUID().toString(), caseId, dealer, dealer, VIN);
    return dealer;
  }

  /** The written quote from the Spanish dealer, verified as itemized, as ticket 63's path does it. */
  private static JsonNode receiveAndVerify(RunningApplication app, RestClient client, String base, String caseId, String token) throws Exception {
    var jdbc = app.context().getBean(JdbcTemplate.class);
    String relayAddress = get(client, base + "/relay", token).path("address").asText();
    var received = app.context().getBean(RelayService.class).receive(new DealerMailRelay.InboundMail("written-quote", relayAddress,
        "ventas@sur.example", "Cotización", "Selling price $25,000 and OTD $26,800.\n" + "Itemized source line\n".repeat(56), List.of()));
    String documentId = jdbc.queryForObject("select document_id from dealer_relay_attachments where message_id = ?", String.class, received.messageId());
    awaitExtraction(app, jdbc.queryForObject("select id from document_processing_operations where document_id = ?", String.class, documentId), token);
    var extraction = get(client, base + "/documents/" + documentId + "/extractions/latest", token);
    String corrections = base + "/documents/" + documentId + "/extractions/" + extraction.path("id").asText() + "/corrections";
    post(client, corrections, Map.of("targetType", "CANDIDATE", "targetId", find(extraction.path("candidates"), "field", "AMOUNT_FINANCED").path("id").asText(),
        "normalizedValue", "2680000", "normalizedUnit", "USD_MINOR"), "finance", token);
    post(client, corrections, Map.of("targetType", "LINE_ITEM", "targetId", find(extraction.path("lineItems"), "category", "UNKNOWN").path("id").asText(),
        "category", "DEALER_FEE", "amountMinor", 89900), "fee", token);
    var candidates = new ArrayList<String>(); var lines = new ArrayList<String>();
    extraction.path("candidates").forEach(value -> candidates.add(value.path("id").asText()));
    extraction.path("lineItems").forEach(value -> lines.add(value.path("id").asText()));
    return post(client, base + "/dealer-offers", Map.of("extractionId", extraction.path("id").asText(),
        "confirmedCandidateIds", candidates, "confirmedLineItemIds", lines), "itemized", token);
  }

  private static JsonNode language(RestClient client, String quotes, String token, String dealer, String language) throws Exception {
    return new ObjectMapper().readTree(client.put().uri(quotes + "/dealers/" + dealer + "/email-language")
        .contentType(MediaType.APPLICATION_JSON).header("Case-Access-Token", token)
        .body(Map.of("language", language)).retrieve().body(String.class));
  }

  private static Map<String, Object> approval(JsonNode request) {
    return Map.of("approved", true, "text", request.at("/gatedDraft/draft/text").asText());
  }

  private static JsonNode approveMandate(RestClient client, String base, String token, String key) throws Exception {
    var proposal = get(client, base + "/mandates/proposal", token);
    return post(client, base + "/mandates", Map.of("planVersion", proposal.path("planVersion").asInt(), "targetMinor", proposal.path("targetMinor").asLong(),
        "ceilingMinor", proposal.path("ceilingMinor").asLong(), "approved", true), key, token);
  }

  private static byte[] zipEntry(byte[] bytes, String name) throws Exception {
    try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
      for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) if (entry.getName().equals(name)) return zip.readAllBytes();
    }
    throw new AssertionError("Missing archive entry: " + name);
  }
}

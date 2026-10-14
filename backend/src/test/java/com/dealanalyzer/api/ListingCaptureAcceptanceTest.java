package com.dealanalyzer.api;

import static com.dealanalyzer.api.DocumentExtractionAcceptanceTest.*;
import static com.dealanalyzer.api.HttpAcceptanceHarness.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Ticket 115: the Buyer's own capture of the dealer's listing, confirmed into a Listing Snapshot and set beside
 * the price on the Dealer Offer and on desk paperwork. Each capture is one of the labelled synthetic fixtures in
 * {@code fixtures/listing/listing-captures.v1.json}.
 */
class ListingCaptureAcceptanceTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String VIN = "1HGCM82633A004352";
  private static final String OTHER_VIN = "1M8GDM9AXKP042788";

  @TempDir Path directory;

  @Test void capturesAreConfirmedIntoSnapshotsAndTheirPriceIsCheckedAgainstOfferDeskAndReport() throws Exception {
    DeskStartAcceptanceTest.paperwork = "AGREED";
    var fixtures = new HashMap<String, JsonNode>();
    try (var input = getClass().getResourceAsStream("/fixtures/listing/listing-captures.v1.json")) {
      JSON.readTree(input).path("fixtures").forEach(fixture -> fixtures.put(fixture.path("label").asText(), fixture));
    }
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("listingcapture", true,
        DeskStartAcceptanceTest.Configuration.class, "--case.deletion.delay=PT1H",
        "--document.storage.path=" + directory.resolve("objects"),
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef")) {
      var client = app.client();
      var jdbc = app.context().getBean(JdbcTemplate.class);
      var created = post(client, "/api/v1/cases", validCase(), "case");
      String caseId = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      String base = "/api/v1/cases/" + caseId, captures = base + "/listing-captures";
      TestDriveAcceptanceTest.agree(app, client, base, caseId, token);
      assertThat(find(get(client, base + "/dealer-offers/latest", token).at("/terms/candidates"), "field", "SELLING_PRICE")
          .path("value").asText()).isEqualTo("2500000");
      // A report saved before any capture keeps its shape.
      var before = post(client, base + "/analyses", Map.of(), "report-before", token);
      awaitCompleted(client, before.at("/operation/id").asText(), token);
      var savedBefore = get(client, base + "/reports/latest", token);
      assertThat(savedBefore.path("analysis").has("listingPriceCheck")).isFalse();
      String statusBefore = status(jdbc, caseId);
      int offersBefore = jdbc.queryForObject("select count(*) from dealer_offers where case_id = ?", Integer.class, caseId);
      assertThat(get(client, captures, token).path("snapshot").isNull()).isTrue();

      // Unreadable capture: nothing is read, and the Buyer types what the ad shows without stopping.
      var unreadable = awaitRead(client, captures, token, uploadBlank(client, captures, token, "blank-capture"));
      assertThat(unreadable.at("/reading/status").asText()).isEqualTo("UNREADABLE");
      assertThat(unreadable.at("/reading/warnings/0").asText()).isNotBlank();
      assertThat(jdbc.queryForObject("select purpose from source_documents where id = ?", String.class,
          unreadable.path("documentId").asText())).isEqualTo("LISTING_CAPTURE");
      var unconfirmed = TradeVehicleAcceptanceTest.call(client, "POST", captures + "/confirmation",
          Map.of("documentId", unreadable.path("documentId").asText(), "advertisedPriceMinor", 2_450_000), token, null);
      assertThat(unconfirmed.status()).isEqualTo(400);
      assertThat(unconfirmed.body().path("field").asText()).isEqualTo("confirmed");
      var typed = confirm(client, captures, token, Map.of("documentId", unreadable.path("documentId").asText(),
          "advertisedPriceMinor", fixtures.get("unreadable-capture").at("/expectedCheck/typedPriceMinor").asLong(), "confirmed", true));
      assertThat(typed.at("/snapshot/source").asText()).isEqualTo("BUYER_SUPPLIED_LISTING_CAPTURE");
      assertThat(typed.at("/snapshot/providerVersion").asText()).isEqualTo("buyer-typed-listing.v1");
      assertThat(typed.at("/snapshot/licenseMetadata").asText()).contains("Buyer's own capture", "not redistributed");
      assertThat(typed.at("/check/status").asText()).isEqualTo("HIGHER");
      assertThat(typed.at("/check/differenceMinor").asLong()).isEqualTo(50_000);
      assertThat(typed.at("/check/paperwork").asText()).isEqualTo("DEALER_OFFER");

      // Matching price: the fields read from pasted text carry their regions and are confirmed as read.
      var matching = awaitRead(client, captures, token, paste(client, captures, token, fixtures.get("matching-price"), "matching"));
      assertThat(matching.at("/reading/status").asText()).isEqualTo("READ");
      var price = find(matching.at("/reading/fields"), "field", "LISTING_PRICE");
      assertThat(price.path("value").asText()).isEqualTo("2500000");
      assertThat(price.at("/evidence/pageNumber").asInt()).isEqualTo(1);
      assertThat(price.at("/evidence/height").asDouble()).isBetween(0.0, 0.05);
      byte[] page = client.get().uri(captures + "/" + matching.path("documentId").asText() + "/pages/1/image")
          .header("Case-Access-Token", token).retrieve().body(byte[].class);
      assertThat(page).startsWith(new byte[] {(byte) 0x89, 'P', 'N', 'G'});
      var same = confirm(client, captures, token, confirmation(matching, List.of()));
      assertThat(same.at("/check/status").asText()).isEqualTo("SAME");
      assertThat(same.at("/check/summary").asText()).contains("same as the listing you saved on October 1, 2026");
      assertThat(same.at("/snapshot/capturedOn").asText()).isEqualTo("2026-10-01");
      assertThat(same.path("identityConflicts")).isEmpty();

      // Conditional ad price, lower than the paperwork: the difference, the date, the question and the fine print.
      var conditional = awaitRead(client, captures, token, paste(client, captures, token, fixtures.get("conditional-ad-price"), "conditional"));
      var conditions = new ArrayList<String>();
      for (var field : conditional.at("/reading/fields"))
        if (field.path("field").asText().equals("LISTING_PRICE_CONDITIONS")) conditions.add(field.path("value").asText());
      assertThat(conditions).hasSize(2).anyMatch(condition -> condition.contains("financing through the dealer"));
      var higher = confirm(client, captures, token, confirmation(conditional, conditions));
      assertThat(higher.at("/check/status").asText()).isEqualTo("HIGHER");
      assertThat(higher.at("/check/differenceMinor").asLong()).isEqualTo(100_500);
      assertThat(higher.at("/check/priceConditions")).hasSize(2);
      assertThat(status(jdbc, caseId)).as("a capture never changes the case's review status").isEqualTo(statusBefore);
      assertThat(jdbc.queryForObject("select count(*) from dealer_offers where case_id = ?", Integer.class, caseId))
          .as("a capture never becomes a Dealer Offer").isEqualTo(offersBefore);

      // Desk paperwork for the agreed car: the Desk Check and the first result both show the advertised price.
      DeskStartAcceptanceTest.paperwork = "ADD_ON";
      String desk = uploadPaperwork(app, base, token, "desk");
      var deskCheck = get(client, base + "/desk-check", token);
      assertThat(deskCheck.at("/listingPrice/status").asText()).isEqualTo("HIGHER");
      assertThat(deskCheck.at("/listingPrice/paperwork").asText()).isEqualTo("DESK_PAPERWORK");
      assertThat(deskCheck.at("/listingPrice/deskQuestion").asText()).isEqualTo(
          "The listing on " + today() + " showed $23,995. Why is the price on this paperwork $25,000?");
      assertThat(deskCheck.at("/listingPrice/priceConditions")).hasSize(2);
      var first = TradeVehicleAcceptanceTest.call(client, "POST", base + "/desk-start/result",
          Map.of("documentId", desk, "route", "AGREED_CAR"), token, null).body();
      var listingFinding = find(first.path("findings"), "code", "LISTING_PRICE_HIGHER");
      assertThat(listingFinding.path("amountMinor").asLong()).isEqualTo(100_500);
      assertThat(listingFinding.path("deskQuestion").asText()).contains("$23,995", "$25,000");
      assertThat(first.at("/listingPrice/priceConditions")).hasSize(2);

      // A fresh report carries the check; the report saved before it is unchanged.
      var after = post(client, base + "/analyses", Map.of(), "report-after", token);
      awaitCompleted(client, after.at("/operation/id").asText(), token);
      var report = get(client, base + "/reports/latest", token);
      assertThat(report.at("/analysis/listingPriceCheck/status").asText()).isEqualTo("HIGHER");
      assertThat(report.at("/analysis/listingPriceCheck/priceConditions")).hasSize(2);
      assertThat(report.at("/evidence/listingCapture/source").asText()).isEqualTo("BUYER_SUPPLIED_LISTING_CAPTURE");
      assertThat(get(client, base + "/reports/" + savedBefore.path("version").asInt(), token)).isEqualTo(savedBefore);

      // Listing VIN mismatch: the existing blocking identity conflict, and prices are not compared.
      var mismatch = awaitRead(client, captures, token, paste(client, captures, token, fixtures.get("listing-vin-mismatch"), "mismatch"));
      var blocked = confirm(client, captures, token, confirmation(mismatch, List.of()));
      assertThat(blocked.path("identityConflicts")).hasSize(1);
      assertThat(blocked.at("/snapshot/vin").asText()).isEqualTo(OTHER_VIN);
      assertThat(blocked.at("/check/status").asText()).isEqualTo("DIFFERENT_VIN");
      var evidence = get(client, base + "/vehicle-evidence", token);
      assertThat(evidence.path("status").asText()).isEqualTo("IDENTITY_CONFLICT");
      assertThat(evidence.path("reliableForAnalysis").asBoolean()).isFalse();
      assertThat(find(evidence.path("identityConflicts"), "listingValue", OTHER_VIN).path("confirmedValue").asText()).isEqualTo(VIN);
      assertThat(evidence.at("/vehicle/vin").asText()).isEqualTo(VIN);
      assertThat(get(client, base + "/desk-check", token).at("/listingPrice/status").asText()).isEqualTo("DIFFERENT_VIN");
      var other = TradeVehicleAcceptanceTest.call(client, "POST", base + "/desk-start/result",
          Map.of("documentId", desk, "route", "AGREED_CAR"), token, null).body();
      assertThat(find(other.path("findings"), "code", "LISTING_VIN_DIFFERENT").path("deskQuestion").asText()).contains(OTHER_VIN);

      // Captures and snapshots are exported with the case and deleted with it.
      var exported = post(client, base + "/exports", Map.of("format", "ZIP", "reportVersion", 0), "export", token);
      byte[] zip = client.get().uri(base + "/exports/" + exported.path("id").asText()).header("Case-Access-Token", token)
          .retrieve().body(byte[].class);
      assertThat(entry(zip, "case-data.json")).contains("listing_capture_readings", "BUYER_SUPPLIED_LISTING_CAPTURE",
          "price_conditions", "LISTING_CAPTURE");
      post(client, base + "/deletion", Map.of(), "delete", token);
      app.context().getBean(com.dealanalyzer.buyingcase.persistence.CaseDeletionWorker.class).runOnce();
      assertThat(get(client, base + "/deletion", token).path("state").asText()).isEqualTo("DELETED");
      for (String table : List.of("listing_capture_readings", "listing_snapshots", "source_documents", "vehicle_identity_conflicts"))
        assertThat(jdbc.queryForObject("select count(*) from " + table + " where case_id = ?", Integer.class, caseId)).as(table).isZero();
    } finally {
      DeskStartAcceptanceTest.paperwork = "AGREED";
    }
  }

  /** The capture text as the Buyer would paste it. */
  static String paste(RestClient client, String captures, String token, JsonNode fixture, String key) throws Exception {
    var view = JSON.readTree(client.post().uri(captures + "/text").header("Case-Access-Token", token)
        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("text", fixture.path("text").asText())).retrieve().body(String.class));
    return view.at("/captures/0/documentId").asText();
  }

  static String uploadBlank(RestClient client, String captures, String token, String key) throws Exception {
    var parts = new LinkedMultiValueMap<String, Object>();
    parts.add("file", new NamedBytes(key + ".pdf", twoPagePdf()));
    var view = JSON.readTree(client.post().uri(captures).contentType(MediaType.MULTIPART_FORM_DATA)
        .header("Idempotency-Key", key).header("Case-Access-Token", token).body(parts).retrieve().body(String.class));
    return view.at("/captures/0/documentId").asText();
  }

  static JsonNode awaitRead(RestClient client, String captures, String token, String documentId) throws Exception {
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(15).toNanos();
    JsonNode capture;
    do {
      capture = find(get(client, captures, token).path("captures"), "documentId", documentId);
      if (capture.path("reading").isObject()) return capture;
      assertThat(capture.path("stage").asText()).isNotEqualTo("FAILED");
      Thread.sleep(150);
    } while (System.nanoTime() < deadline);
    throw new AssertionError("The capture was not read: " + capture);
  }

  /** Every field as read, confirmed unchanged. */
  private static Map<String, Object> confirmation(JsonNode capture, List<String> conditions) {
    var body = new LinkedHashMap<String, Object>();
    body.put("documentId", capture.path("documentId").asText());
    body.put("confirmed", true);
    body.put("priceConditions", conditions);
    var names = Map.of("LISTING_PRICE", "advertisedPriceMinor", "VEHICLE_VIN", "vin", "VEHICLE_YEAR", "year",
        "VEHICLE_MAKE", "make", "VEHICLE_MODEL", "model", "VEHICLE_TRIM", "trim", "ODOMETER", "mileage",
        "DEALER_NAME", "dealerName", "CAPTURE_DATE", "capturedOn");
    for (var field : capture.at("/reading/fields")) {
      String name = names.get(field.path("field").asText());
      if (name != null && field.path("value").isTextual()) body.put(name, field.path("value").asText());
    }
    return body;
  }

  private static JsonNode confirm(RestClient client, String captures, String token, Map<String, Object> body) {
    var result = TradeVehicleAcceptanceTest.call(client, "POST", captures + "/confirmation", body, token, null);
    assertThat(result.status()).as(result.toString()).isEqualTo(200);
    return result.body();
  }

  private static String uploadPaperwork(RunningApplication app, String base, String token, String key) throws Exception {
    var parts = new LinkedMultiValueMap<String, Object>();
    parts.add("file", new NamedBytes(key + ".pdf", twoPagePdf()));
    var started = JSON.readTree(app.client().post().uri(base + "/documents").contentType(MediaType.MULTIPART_FORM_DATA)
        .header("Idempotency-Key", key).header("Case-Access-Token", token).body(parts).retrieve().body(String.class));
    awaitExtraction(app, started.at("/operation/id").asText(), token);
    return started.at("/document/id").asText();
  }

  private static String status(JdbcTemplate jdbc, String caseId) {
    return jdbc.queryForObject("select status from analysis_cases where id = ?", String.class, caseId);
  }

  private static String today() {
    return java.time.LocalDate.now(java.time.ZoneId.of("America/New_York"))
        .format(java.time.format.DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.US));
  }

  private static String entry(byte[] zip, String name) throws Exception {
    try (var input = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
      for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry())
        if (entry.getName().equals(name)) return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
    throw new AssertionError("Missing " + name);
  }
}

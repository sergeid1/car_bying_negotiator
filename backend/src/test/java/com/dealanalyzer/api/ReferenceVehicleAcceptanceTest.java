package com.dealanalyzer.api;

import static org.assertj.core.api.Assertions.*;

import com.dealanalyzer.account.AccountDeletionWorker;
import com.dealanalyzer.journey.JourneyService;
import com.dealanalyzer.journey.vin.FakeVinDecoder;
import com.dealanalyzer.journey.vin.VinDecoder;
import com.dealanalyzer.journey.vin.VinDecoding;
import com.dealanalyzer.recovery.RecoveryEmail;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

@ExtendWith(OutputCaptureExtension.class)
class ReferenceVehicleAcceptanceTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String KEY = "--document.storage.encryption-key=0123456789abcdef0123456789abcdef";
  private static final String SESSION = "Account-Session-Token";
  private static final String RAV4 = "2T3P1RFV5MW000001";
  private static final String CRV = "5J6RW2H52ML000002";
  private static final String SILVERADO = "1GCUYHED0KZ000003";
  private static final String PARTIAL_RAV4 = "JTMRWRFV3LD000004";
  private static final String UNKNOWN = "3VW000003AA000006";
  private static final String CLASSIC = "1HGCM82633A004352";
  static final CopyOnWriteArrayList<String> links = new CopyOnWriteArrayList<>();
  static final AtomicInteger decodes = new AtomicInteger();

  /** Counts decoder calls so the test can prove an invalid VIN never reaches the decoder. */
  @TestConfiguration static class Configuration {
    @Bean @Primary RecoveryEmail referenceEmail() {
      return new RecoveryEmail() {
        public void send(String recipient, String link) { links.add(link); }
        @Override public void send(String recipient, String link, Purpose purpose) { links.add(link); }
      };
    }

    @Bean @Primary VinDecoder countingDecoder(FakeVinDecoder fake) {
      return vin -> { decodes.incrementAndGet(); return fake.decode(vin); };
    }
  }

  @TempDir Path directory;

  @BeforeEach void clear() { links.clear(); decodes.set(0); }

  @Test void aBuyerAddsDecodesMarksAndRemovesReferenceVehiclesAsBriefVersions(CapturedOutput output) throws Exception {
    try (var app = start("reference")) {
      var client = app.client();
      String session = signIn(client, "buyer@example.com");
      assertThat(exchange(client, "GET", "/api/v1/journeys/brief-options", null, session).body()
          .at("/bounds/maxReferenceVehicles").asInt()).isEqualTo(5);
      String id = create(client, session);
      String base = "/api/v1/journeys/" + id;

      // A Buyer Account session is required.
      assertThat(exchange(client, "POST", base + "/reference-vehicles", Map.of("basedOnVersion", 1, "vin", RAV4), null)
          .status()).isEqualTo(401);

      // An invalid VIN is refused with a checksum message before any decoder call, and nothing is saved.
      var invalid = exchange(client, "POST", base + "/reference-vehicles", Map.of("basedOnVersion", 1, "vin", "2T3P1RFV0MW000001"), session);
      assertThat(invalid.status()).isEqualTo(400);
      assertThat(invalid.body().path("code").asText()).isEqualTo("VIN_INVALID");
      assertThat(invalid.body().at("/errors/0/field").asText()).isEqualTo("vin");
      assertThat(invalid.body().at("/errors/0/message").asText()).contains("checksum");
      assertThat(invalid.body().toString()).doesNotContain("2T3P1RFV0MW000001");
      assertThat(exchange(client, "POST", base + "/reference-vehicles", Map.of("basedOnVersion", 1, "vin", "TOO-SHORT"), session)
          .body().path("code").asText()).isEqualTo("VIN_INVALID");
      assertThat(decodes).hasValue(0);
      assertThat(exchange(client, "GET", base, null, session).body().at("/brief/version").asInt()).isEqualTo(1);

      // A valid VIN is decoded and added as brief version 2; every attribute names its source and version.
      var added = exchange(client, "POST", base + "/reference-vehicles", Map.of("basedOnVersion", 1, "vin", RAV4.toLowerCase()), session);
      assertThat(added.status()).isEqualTo(200);
      assertThat(added.body().at("/brief/version").asInt()).isEqualTo(2);
      var rav4 = added.body().at("/referenceVehicles/0");
      assertThat(rav4.path("vin").asText()).isEqualTo(RAV4);
      assertThat(rav4.path("outcome").asText()).isEqualTo("DECODED");
      assertThat(rav4.path("attributes").findValuesAsText("attribute")).containsExactly("MODEL_YEAR", "MAKE", "MODEL", "TRIM",
          "BODY", "DRIVETRAIN", "ENGINE", "TRANSMISSION");
      assertThat(rav4.path("attributes").findValuesAsText("source")).containsOnly(FakeVinDecoder.SOURCE);
      assertThat(rav4.path("attributes").findValuesAsText("decoderVersion")).containsOnly(FakeVinDecoder.VERSION);
      assertThat(rav4.path("attributes").findValuesAsText("relevant")).containsOnly("false");
      String rav4Id = rav4.path("referenceId").asText();
      assertThat(decodes).hasValue(1);
      // A retried add of a held VIN returns the journey unchanged without decoding again.
      assertThat(exchange(client, "POST", base + "/reference-vehicles", Map.of("basedOnVersion", 1, "vin", RAV4), session)
          .body().at("/brief/version").asInt()).isEqualTo(2);
      assertThat(decodes).hasValue(1);
      // A stale add is refused.
      assertThat(exchange(client, "POST", base + "/reference-vehicles", Map.of("basedOnVersion", 1, "vin", CRV), session)
          .body().path("code").asText()).isEqualTo("BRIEF_CHANGED");

      // A partial decode leaves unsupplied attributes absent; an undecodable VIN is kept, shown with no attributes.
      var partial = exchange(client, "POST", base + "/reference-vehicles", Map.of("basedOnVersion", 2, "vin", PARTIAL_RAV4), session).body();
      assertThat(partial.at("/referenceVehicles/1/attributes").findValuesAsText("attribute"))
          .containsExactly("MODEL_YEAR", "MAKE", "MODEL", "BODY", "DRIVETRAIN");
      var undecodable = exchange(client, "POST", base + "/reference-vehicles", Map.of("basedOnVersion", 3, "vin", UNKNOWN), session).body();
      assertThat(undecodable.at("/referenceVehicles/2/outcome").asText()).isEqualTo("UNDECODABLE");
      assertThat(undecodable.at("/referenceVehicles/2/attributes").size()).isZero();
      assertThat(undecodable.at("/brief/version").asInt()).isEqualTo(4);

      // An unavailable decoder saves nothing and says so.
      var unavailable = exchange(client, "POST", base + "/reference-vehicles",
          Map.of("basedOnVersion", 4, "vin", FakeVinDecoder.UNAVAILABLE_VIN), session);
      assertThat(unavailable.status()).isEqualTo(503);
      assertThat(unavailable.body().path("code").asText()).isEqualTo("VIN_DECODER_UNAVAILABLE");
      assertThat(unavailable.body().path("detail").asText()).contains("Nothing was saved and nothing was guessed");
      assertThat(exchange(client, "GET", base, null, session).body().at("/brief/version").asInt()).isEqualTo(4);

      // Up to five per brief.
      exchange(client, "POST", base + "/reference-vehicles", Map.of("basedOnVersion", 4, "vin", CRV), session);
      exchange(client, "POST", base + "/reference-vehicles", Map.of("basedOnVersion", 5, "vin", SILVERADO), session);
      int before = decodes.get();
      var sixth = exchange(client, "POST", base + "/reference-vehicles", Map.of("basedOnVersion", 6, "vin", CLASSIC), session);
      assertThat(sixth.status()).isEqualTo(422);
      assertThat(sixth.body().path("code").asText()).isEqualTo("REFERENCE_VEHICLE_LIMIT");
      assertThat(decodes).hasValue(before);

      // Marking relevance creates a version; only relevant attributes are offered to matching.
      var marked = exchange(client, "PUT", base + "/reference-vehicles/" + rav4Id + "/relevance",
          Map.of("basedOnVersion", 6, "relevantAttributes", List.of("TRIM", "DRIVETRAIN")), session).body();
      assertThat(marked.at("/brief/version").asInt()).isEqualTo(7);
      var service = app.context().getBean(JourneyService.class);
      String accountId = new JdbcTemplate(app.context().getBean(javax.sql.DataSource.class))
          .queryForObject("select account_id from buying_journeys where id = ?", String.class, id);
      var current = service.referenceVehicles(accountId, id);
      assertThat(current).hasSize(5);
      assertThat(current.getFirst().relevantAttributes()).extracting(a -> a.attribute().name() + "=" + a.value())
          .containsExactly("TRIM=XLE", "DRIVETRAIN=All-wheel drive");
      assertThat(current.get(1).relevantAttributes()).isEmpty();
      // The same choice again changes nothing; an attribute that was not decoded is refused.
      assertThat(exchange(client, "PUT", base + "/reference-vehicles/" + rav4Id + "/relevance",
          Map.of("basedOnVersion", 6, "relevantAttributes", List.of("DRIVETRAIN", "TRIM")), session).body()
          .at("/brief/version").asInt()).isEqualTo(7);
      String partialId = marked.at("/referenceVehicles/1/referenceId").asText();
      assertThat(exchange(client, "PUT", base + "/reference-vehicles/" + partialId + "/relevance",
          Map.of("basedOnVersion", 7, "relevantAttributes", List.of("TRIM")), session).body().path("code").asText())
          .isEqualTo("RELEVANCE_INVALID");

      // Editing the brief carries the Reference Vehicles and their relevance into the new version.
      var edited = BuyingJourneyAcceptanceTest.brief(); edited.put("radiusMiles", 75);
      var revised = exchange(client, "POST", base + "/briefs", Map.of("basedOnVersion", 7, "brief", edited), session).body();
      assertThat(revised.at("/brief/version").asInt()).isEqualTo(8);
      assertThat(revised.path("referenceVehicles")).hasSize(5);
      assertThat(revised.at("/referenceVehicles/0/attributes").findValuesAsText("relevant")).contains("true");

      // Removing creates a version; a retried removal returns it; earlier versions keep their vehicles.
      var removed = exchange(client, "DELETE", base + "/reference-vehicles/" + rav4Id + "?basedOnVersion=8", null, session);
      assertThat(removed.status()).isEqualTo(200);
      assertThat(removed.body().at("/brief/version").asInt()).isEqualTo(9);
      assertThat(removed.body().path("referenceVehicles").findValuesAsText("vin")).doesNotContain(RAV4).hasSize(4);
      assertThat(exchange(client, "DELETE", base + "/reference-vehicles/" + rav4Id + "?basedOnVersion=8", null, session)
          .body().at("/brief/version").asInt()).isEqualTo(9);
      assertThat(exchange(client, "DELETE", base + "/reference-vehicles/" + rav4Id + "?basedOnVersion=9", null, session)
          .status()).isEqualTo(404);
      var second = exchange(client, "GET", base + "/briefs/2/reference-vehicles", null, session);
      assertThat(second.body().findValuesAsText("vin")).containsExactly(RAV4);
      assertThat(exchange(client, "GET", base + "/briefs/1/reference-vehicles", null, session).body().size()).isZero();

      // Another Buyer cannot read or change them.
      String other = signIn(client, "other@example.com");
      assertThat(exchange(client, "GET", base + "/briefs/2/reference-vehicles", null, other).status()).isEqualTo(404);
      assertThat(exchange(client, "POST", base + "/reference-vehicles", Map.of("basedOnVersion", 9, "vin", RAV4), other)
          .status()).isEqualTo(404);
      String crvId = removed.body().at("/referenceVehicles/2/referenceId").asText();
      assertThat(exchange(client, "DELETE", base + "/reference-vehicles/" + crvId + "?basedOnVersion=9", null, other)
          .status()).isEqualTo(404);

      // Deleting the journey deletes every Reference VIN with it.
      var jdbc = new JdbcTemplate(app.context().getBean(javax.sql.DataSource.class));
      assertThat(jdbc.queryForObject("select count(*) from reference_vehicles where journey_id = ?", Long.class, id)).isPositive();
      var deleted = exchange(client, "DELETE", base, null, session).body();
      assertThat(deleted.path("verification").asText()).isEqualTo("JOURNEY_BRIEF_VERSIONS_AND_REFERENCE_VEHICLES_ABSENT_V1");
      assertThat(jdbc.queryForObject("select count(*) from reference_vehicles where journey_id = ?", Long.class, id)).isZero();
    }
    // Reference VINs are personal data and stay out of the logs.
    assertThat(output.getAll()).doesNotContain(RAV4, CRV, UNKNOWN);
  }

  @Test void accountExportIncludesReferenceVehiclesAndAccountDeletionRemovesThem() throws Exception {
    try (var app = start("reference-account")) {
      var client = app.client();
      String session = signIn(client, "buyer@example.com");
      String id = create(client, session);
      exchange(client, "POST", "/api/v1/journeys/" + id + "/reference-vehicles", Map.of("basedOnVersion", 1, "vin", RAV4), session);

      byte[] archive = client.post().uri("/api/v1/account/export").header(SESSION, session).retrieve().body(byte[].class);
      JsonNode account = null;
      String readme = null;
      try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(archive))) {
        for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
          if (entry.getName().equals("account.json")) account = JSON.readTree(zip.readAllBytes());
          if (entry.getName().equals("README.txt")) readme = new String(zip.readAllBytes());
        }
      }
      assertThat(readme).contains("Reference Vehicles");
      var held = account.at("/buyingJourneys/0/referenceVehicles");
      assertThat(held).hasSize(1);
      assertThat(held.at("/0/briefVersion").asInt()).isEqualTo(2);
      assertThat(held.at("/0/vehicles/0/vin").asText()).isEqualTo(RAV4);

      assertThat(exchange(client, "POST", "/api/v1/account/deletion", Map.of(), session).status()).isEqualTo(202);
      app.context().getBean(AccountDeletionWorker.class).runOnce();
      var jdbc = new JdbcTemplate(app.context().getBean(javax.sql.DataSource.class));
      assertThat(jdbc.queryForObject("select count(*) from reference_vehicles", Long.class)).isZero();
      assertThat(jdbc.queryForObject("select count(*) from buying_journeys where id = ?", Long.class, id)).isZero();
    }
  }

  @Test void theFakeDecoderIsDeterministicAndNeverGuesses() {
    var fake = new FakeVinDecoder();
    assertThat(fake.decode(RAV4)).isEqualTo(fake.decode(RAV4));
    assertThat(fake.decode(UNKNOWN).outcome()).isEqualTo(VinDecoding.Outcome.UNDECODABLE);
    assertThat(fake.decode(UNKNOWN).attributes()).isEmpty();
  }

  private String create(RestClient client, String session) {
    return exchange(client, "POST", "/api/v1/journeys", BuyingJourneyAcceptanceTest.brief(), session, UUID.randomUUID().toString())
        .body().path("id").asText();
  }

  private HttpAcceptanceHarness.RunningApplication start(String database) {
    return new HttpAcceptanceHarness(directory).startWithConfiguration(database, false, Configuration.class, KEY,
        "--case.deletion.delay=PT1H");
  }

  private static String signIn(RestClient client, String email) throws Exception {
    HttpAcceptanceHarness.post(client, "/api/v1/account/sign-in-requests", Map.of("email", email), UUID.randomUUID().toString());
    String secret = links.getLast().split("#sign-in=")[1];
    return HttpAcceptanceHarness.post(client, "/api/v1/account/sign-in/verify", Map.of("token", secret))
        .path("sessionToken").asText();
  }

  record Result(int status, JsonNode body) {}

  private static Result exchange(RestClient client, String method, String path, Object body, String session) {
    return exchange(client, method, path, body, session, null);
  }

  private static Result exchange(RestClient client, String method, String path, Object body, String session, String key) {
    var request = client.method(HttpMethod.valueOf(method)).uri(path);
    if (session != null) request.header(SESSION, session);
    if (key != null) request.header("Idempotency-Key", key);
    if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
    return request.exchange((req, response) -> {
      byte[] bytes = response.getBody().readAllBytes();
      return new Result(response.getStatusCode().value(), bytes.length == 0 ? JSON.nullNode() : JSON.readTree(bytes));
    });
  }
}

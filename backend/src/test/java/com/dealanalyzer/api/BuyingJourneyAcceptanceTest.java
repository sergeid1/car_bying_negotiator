package com.dealanalyzer.api;

import static org.assertj.core.api.Assertions.*;

import com.dealanalyzer.account.AccountDeletionWorker;
import com.dealanalyzer.recovery.RecoveryEmail;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
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
class BuyingJourneyAcceptanceTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String KEY = "--document.storage.encryption-key=0123456789abcdef0123456789abcdef";
  private static final String SESSION = "Account-Session-Token";
  static final CopyOnWriteArrayList<String> links = new CopyOnWriteArrayList<>();

  @TestConfiguration static class EmailConfiguration {
    @Bean @Primary RecoveryEmail journeyEmail() {
      return new RecoveryEmail() {
        public void send(String recipient, String link) { links.add(link); }
        @Override public void send(String recipient, String link, Purpose purpose) { links.add(link); }
      };
    }
  }

  @TempDir Path directory;

  @BeforeEach void clear() { links.clear(); }

  static Map<String, Object> brief() {
    var brief = new HashMap<String, Object>();
    brief.put("yearFrom", 2020); brief.put("yearTo", 2024);
    brief.put("makeId", "toyota"); brief.put("modelId", "toyota-rav4");
    brief.put("trimIds", List.of("toyota-rav4-limited", "toyota-rav4-xle"));
    brief.put("maxMileage", 60000); brief.put("priceCeiling", 32000);
    brief.put("postalCode", "27601"); brief.put("radiusMiles", 50);
    brief.put("mustHaveFeatures", List.of("all-wheel-drive"));
    brief.put("niceToHaveFeatures", List.of("heated-seats", "sunroof-or-moonroof"));
    return brief;
  }

  @Test void aSignedInBuyerCreatesVersionsListsOpensAndDeletesJourneys() throws Exception {
    try (var app = start("journeys")) {
      var client = app.client();
      String session = signIn(client, "buyer@example.com");

      // Every journey endpoint needs a live account session; a case token is not enough.
      assertThat(exchange(client, "GET", "/api/v1/journeys", null, null, null).status()).isEqualTo(401);
      assertThat(exchange(client, "POST", "/api/v1/journeys", brief(), "wrong", "k").body().path("code").asText())
          .isEqualTo("SESSION_ENDED");

      var options = exchange(client, "GET", "/api/v1/journeys/brief-options", null, session, null);
      assertThat(options.status()).isEqualTo(200);
      assertThat(options.body().at("/reference/version").asText()).isEqualTo("vehicle-reference-2026-10-v1");
      assertThat(options.body().at("/bounds/maxRadiusMiles").asInt()).isEqualTo(500);

      var created = exchange(client, "POST", "/api/v1/journeys", brief(), session, "create-1");
      assertThat(created.status()).isEqualTo(201);
      String id = created.body().path("id").asText();
      assertThat(created.body().path("state").asText()).isEqualTo("SHOPPING");
      var v1 = created.body().path("brief");
      assertThat(v1.path("version").asInt()).isEqualTo(1);
      assertThat(v1.path("referenceListVersion").asText()).isEqualTo("vehicle-reference-2026-10-v1");
      assertThat(v1.at("/make/label").asText()).isEqualTo("Toyota");
      assertThat(v1.at("/model/label").asText()).isEqualTo("RAV4");
      // Stored in reference-list order, whatever order the Buyer picked.
      assertThat(v1.path("trims").findValuesAsText("id")).containsExactly("toyota-rav4-xle", "toyota-rav4-limited");
      assertThat(v1.path("priceCeilingMinor").asLong()).isEqualTo(3_200_000L);
      assertThat(v1.path("jurisdiction").asText()).isEqualTo("US-NC");
      // A replayed create returns the same journey instead of a second one.
      assertThat(exchange(client, "POST", "/api/v1/journeys", brief(), session, "create-1").body().path("id").asText())
          .isEqualTo(id);
      assertThat(exchange(client, "POST", "/api/v1/journeys", brief(), session, null).status()).isEqualTo(400);

      // Editing creates version 2; version 1 stays readable and unchanged.
      var edited = brief(); edited.put("priceCeiling", 30000); edited.put("trimIds", List.of());
      var revised = exchange(client, "POST", "/api/v1/journeys/" + id + "/briefs",
          Map.of("basedOnVersion", 1, "brief", edited), session, null);
      assertThat(revised.status()).isEqualTo(200);
      assertThat(revised.body().at("/brief/version").asInt()).isEqualTo(2);
      assertThat(revised.body().at("/brief/trims").size()).isZero();
      assertThat(revised.body().path("versions").findValuesAsText("version")).containsExactly("1", "2");
      // A retried save of the same edit does not create version 3.
      assertThat(exchange(client, "POST", "/api/v1/journeys/" + id + "/briefs",
          Map.of("basedOnVersion", 1, "brief", edited), session, null).body().at("/brief/version").asInt()).isEqualTo(2);
      // A stale edit with different content is refused rather than replacing version 2.
      var stale = exchange(client, "POST", "/api/v1/journeys/" + id + "/briefs",
          Map.of("basedOnVersion", 1, "brief", brief()), session, null);
      assertThat(stale.status()).isEqualTo(409);
      assertThat(stale.body().path("code").asText()).isEqualTo("BRIEF_CHANGED");
      var first = exchange(client, "GET", "/api/v1/journeys/" + id + "/briefs/1", null, session, null).body();
      assertThat(first.path("priceCeilingMinor").asLong()).isEqualTo(3_200_000L);
      assertThat(first.path("trims").size()).isEqualTo(2);
      assertThat(exchange(client, "GET", "/api/v1/journeys/" + id + "/briefs/9", null, session, null).status()).isEqualTo(404);
      var opened = exchange(client, "GET", "/api/v1/journeys/" + id, null, session, null).body();
      assertThat(opened.at("/brief/version").asInt()).isEqualTo(2);

      var jdbc = new JdbcTemplate(app.context().getBean(javax.sql.DataSource.class));
      assertThat(jdbc.queryForObject("select count(*) from shopping_brief_versions where journey_id = ?", Long.class, id)).isEqualTo(2);

      var second = exchange(client, "POST", "/api/v1/journeys", brief(), session, "create-2").body().path("id").asText();
      var list = exchange(client, "GET", "/api/v1/journeys", null, session, null).body();
      assertThat(list.findValuesAsText("id")).containsExactly(second, id);
      assertThat(list.get(1).path("currentVersion").asInt()).isEqualTo(2);
      assertThat(list.get(1).path("model").asText()).isEqualTo("RAV4");

      // Another Buyer cannot see, edit or delete these journeys.
      String other = signIn(client, "other@example.com");
      assertThat(exchange(client, "GET", "/api/v1/journeys", null, other, null).body().size()).isZero();
      assertThat(exchange(client, "GET", "/api/v1/journeys/" + id, null, other, null).status()).isEqualTo(404);
      assertThat(exchange(client, "GET", "/api/v1/journeys/" + id + "/briefs/1", null, other, null).status()).isEqualTo(404);
      assertThat(exchange(client, "POST", "/api/v1/journeys/" + id + "/briefs",
          Map.of("basedOnVersion", 2, "brief", brief()), other, null).status()).isEqualTo(404);
      assertThat(exchange(client, "DELETE", "/api/v1/journeys/" + id, null, other, null).status()).isEqualTo(404);

      var search = app.context().getBean(com.dealanalyzer.shopping.inventory.InventorySearchService.class);
      String accountId = app.context().getBean(com.dealanalyzer.account.AccountJourneyAccess.class).accountId(session);
      String otherAccount = app.context().getBean(com.dealanalyzer.account.AccountJourneyAccess.class).accountId(other);
      assertThatThrownBy(() -> search.search(otherAccount, id))
          .hasMessage("That Buying Journey does not exist in this account. It may have been deleted.");
      var inventory = search.search(accountId, id);
      assertThat(inventory.listings()).hasSize(5);
      assertThat(inventory.notice()).contains("Synthetic");
      String usageId = jdbc.queryForObject("select id from provider_usage where journey_id = ?", String.class, id);

      var deleted = exchange(client, "DELETE", "/api/v1/journeys/" + id, null, session, null);
      assertThat(deleted.status()).isEqualTo(200);
      assertThat(deleted.body().path("state").asText()).isEqualTo("DELETED");
      assertThat(exchange(client, "GET", "/api/v1/journeys/" + id, null, session, null).status()).isEqualTo(404);
      assertThat(exchange(client, "DELETE", "/api/v1/journeys/" + id, null, session, null).status()).isEqualTo(404);
      assertThat(jdbc.queryForObject("select count(*) from shopping_brief_versions where journey_id = ?", Long.class, id)).isZero();
      assertThat(jdbc.queryForObject("select count(*) from provider_usage where journey_id = ?", Long.class, id)).isZero();
      // A late response only updates an existing run; deletion cannot be undone by it.
      var usage = app.context().getBean(com.dealanalyzer.operations.ProviderUsageStore.class);
      usage.inventoryResult(usageId, inventory); usage.inventoryLatency(usageId, 200);
      assertThat(jdbc.queryForObject("select count(*) from provider_usage where id = ?", Long.class, usageId)).isZero();
      assertThatThrownBy(() -> search.search(accountId, id))
          .hasMessage("That Buying Journey does not exist in this account. It may have been deleted.");
      assertThatThrownBy(() -> usage.startInventory(accountId, id, inventory.provider(), "{}"))
          .isInstanceOf(com.dealanalyzer.buyingcase.ResourceNotFoundException.class);
      assertThat(exchange(client, "GET", "/api/v1/journeys", null, session, null).body().findValuesAsText("id"))
          .containsExactly(second);
    }
  }

  @Test void everyFieldIsValidatedAndNamedWithItsCorrection(CapturedOutput output) throws Exception {
    try (var app = start("validation")) {
      var client = app.client();
      String session = signIn(client, "buyer@example.com");
      var bad = new HashMap<String, Object>();
      bad.put("yearFrom", 1989); bad.put("yearTo", 2400); bad.put("makeId", "Toyota RAV4 free text");
      bad.put("maxMileage", -1); bad.put("priceCeiling", 999); bad.put("postalCode", "2760");
      bad.put("radiusMiles", 501); bad.put("mustHaveFeatures", List.of("rocket-boosters"));
      bad.put("niceToHaveFeatures", List.of("heated-seats", "heated-seats"));
      var refused = exchange(client, "POST", "/api/v1/journeys", bad, session, "bad");
      assertThat(refused.status()).isEqualTo(400);
      assertThat(refused.body().path("code").asText()).isEqualTo("BRIEF_INVALID");
      assertThat(refused.body().path("errors").findValuesAsText("field")).containsExactlyInAnyOrder("yearFrom", "yearTo",
          "makeId", "maxMileage", "priceCeiling", "postalCode", "radiusMiles", "mustHaveFeatures", "niceToHaveFeatures");
      assertThat(refused.body().toString()).doesNotContain("free text", "rocket", "2760\"");

      var everything = exchange(client, "POST", "/api/v1/journeys", Map.of(), session, "empty").body();
      assertThat(everything.path("errors").findValuesAsText("field")).containsExactlyInAnyOrder("yearFrom", "yearTo",
          "makeId", "maxMileage", "priceCeiling", "postalCode", "radiusMiles");

      var crossed = brief(); crossed.put("yearFrom", 2025); crossed.put("yearTo", 2021);
      crossed.put("modelId", "honda-civic"); crossed.put("mustHaveFeatures", List.of("heated-seats"));
      var mixed = exchange(client, "POST", "/api/v1/journeys", crossed, session, "mixed").body().path("errors");
      assertThat(mixed.findValuesAsText("field")).containsExactlyInAnyOrder("yearTo", "modelId", "niceToHaveFeatures");
      assertThat(mixed.findValuesAsText("message")).contains("Choose a model of the selected make from the list.");

      var trim = brief(); trim.put("trimIds", List.of("honda-civic-lx"));
      assertThat(exchange(client, "POST", "/api/v1/journeys", trim, session, "trim").body().path("errors")
          .findValuesAsText("field")).containsExactly("trimIds");

      var unreadable = exchange(client, "POST", "/api/v1/journeys", Map.of("yearFrom", "twenty"), session, "unreadable");
      assertThat(unreadable.status()).isEqualTo(400);
      assertThat(unreadable.body().path("code").asText()).isEqualTo("BRIEF_UNREADABLE");

      var jdbc = new JdbcTemplate(app.context().getBean(javax.sql.DataSource.class));
      assertThat(jdbc.queryForObject("select count(*) from buying_journeys", Long.class)).isZero();
    }
    assertThat(output.getAll()).doesNotContain("free text", "rocket-boosters");
  }

  @Test void aZipOutsideSupportedCoverageIsExplainedAndNothingIsStored() throws Exception {
    try (var app = start("location")) {
      var client = app.client();
      String session = signIn(client, "buyer@example.com");
      var unsupported = brief(); unsupported.put("postalCode", "10001");
      var refused = exchange(client, "POST", "/api/v1/journeys", unsupported, session, "ny");
      assertThat(refused.status()).isEqualTo(422);
      assertThat(refused.body().path("code").asText()).isEqualTo("LOCATION_NOT_SERVED");
      assertThat(refused.body().at("/errors/0/field").asText()).isEqualTo("postalCode");
      assertThat(refused.body().at("/errors/0/reason").asText()).isEqualTo("JURISDICTION_NOT_SUPPORTED");
      assertThat(refused.body().at("/errors/0/candidateJurisdictions/0").asText()).isEqualTo("US-NC");
      assertThat(refused.body().path("detail").asText()).contains("does not support").doesNotContain("10001");

      var unknown = brief(); unknown.put("postalCode", "00000");
      assertThat(exchange(client, "POST", "/api/v1/journeys", unknown, session, "unknown").body().at("/errors/0/reason")
          .asText()).isEqualTo("UNRECOGNIZED_POSTAL_CODE");

      // 27048 crosses into Virginia: the Buyer is asked for their state, then it must be one the ZIP touches.
      var crossing = brief(); crossing.put("postalCode", "27048");
      var ask = exchange(client, "POST", "/api/v1/journeys", crossing, session, "cross").body();
      assertThat(ask.at("/errors/0/field").asText()).isEqualTo("jurisdiction");
      assertThat(ask.at("/errors/0/reason").asText()).isEqualTo("POSTAL_CODE_CROSSES_STATE_LINE");
      assertThat(ask.at("/errors/0/candidateJurisdictions").toString()).contains("US-NC", "US-VA");
      crossing.put("jurisdiction", "US-SC");
      assertThat(exchange(client, "POST", "/api/v1/journeys", crossing, session, "cross-sc").status()).isEqualTo(400);
      crossing.put("jurisdiction", "US-VA");
      assertThat(exchange(client, "POST", "/api/v1/journeys", crossing, session, "cross-va").body().at("/errors/0/reason")
          .asText()).isEqualTo("JURISDICTION_NOT_SUPPORTED");
      var jdbc = new JdbcTemplate(app.context().getBean(javax.sql.DataSource.class));
      assertThat(jdbc.queryForObject("select count(*) from buying_journeys", Long.class)).isZero();
      crossing.put("jurisdiction", "US-NC");
      var created = exchange(client, "POST", "/api/v1/journeys", crossing, session, "cross-nc");
      assertThat(created.status()).isEqualTo(201);
      assertThat(created.body().at("/brief/jurisdiction").asText()).isEqualTo("US-NC");
      // A state stated for an unambiguous ZIP must match it.
      var mismatch = brief(); mismatch.put("jurisdiction", "US-VA");
      assertThat(exchange(client, "POST", "/api/v1/journeys", mismatch, session, "mismatch").body().at("/errors/0/field")
          .asText()).isEqualTo("jurisdiction");
    }
  }

  @Test void accountExportIncludesEveryBriefVersionAndAccountDeletionRemovesJourneys() throws Exception {
    try (var app = start("account-lifecycle")) {
      var client = app.client();
      String session = signIn(client, "buyer@example.com");
      String id = exchange(client, "POST", "/api/v1/journeys", brief(), session, "create").body().path("id").asText();
      var edited = brief(); edited.put("radiusMiles", 100);
      exchange(client, "POST", "/api/v1/journeys/" + id + "/briefs", Map.of("basedOnVersion", 1, "brief", edited), session, null);
      String other = signIn(client, "other@example.com");
      String otherId = exchange(client, "POST", "/api/v1/journeys", brief(), other, "other").body().path("id").asText();
      var search = app.context().getBean(com.dealanalyzer.shopping.inventory.InventorySearchService.class);
      var access = app.context().getBean(com.dealanalyzer.account.AccountJourneyAccess.class);
      String accountId = access.accountId(session);
      search.search(accountId, id); search.search(access.accountId(other), otherId);
      var jdbc = new JdbcTemplate(app.context().getBean(javax.sql.DataSource.class));
      var run = jdbc.queryForMap("select * from provider_usage where journey_id = ?", id);
      assertThat(run.get("CASE_ID")).isNull();
      assertThat(run.get("ACCOUNT_ID")).isEqualTo(accountId);
      assertThat(run.get("OPERATION")).isEqualTo("INVENTORY_SEARCH");
      assertThat(run.get("PROVIDER")).isEqualTo("SYNTHETIC_INVENTORY");
      assertThat(run.get("LICENSE")).isEqualTo("SYNTHETIC_DISPLAY_AND_CACHE_PERMITTED");
      assertThat(run.get("RESULT_COUNT")).isEqualTo(5);
      assertThat(run.get("RETRIEVED_AT")).isNotNull();
      assertThat(((Number) run.get("LATENCY_MILLIS")).longValue()).isNotNegative();
      assertThat(((java.math.BigDecimal) run.get("COST_USD"))).isZero();
      assertThat(run.get("STATUS")).isEqualTo("SUCCESS");
      assertThat(run.get("INVENTORY_AVAILABILITY")).isEqualTo("AVAILABLE");
      String query = jdbc.queryForObject("select query_json from provider_usage where journey_id = ?", String.class, id);
      assertThat(JSON.readTree(query).at("/criteria/postalCode").asText()).isEqualTo("27601");
      assertThat(JSON.readTree(query).at("/criteria/briefVersion").asInt()).isEqualTo(2);
      assertThat(JSON.readTree(query).path("radiusMiles").asInt()).isEqualTo(100);
      assertThat(query).doesNotContain(accountId, id, "buyer@example.com");

      byte[] archive = client.post().uri("/api/v1/account/export").header(SESSION, session).retrieve().body(byte[].class);
      JsonNode account = null;
      try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(archive))) {
        for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry())
          if (entry.getName().equals("account.json")) account = JSON.readTree(zip.readAllBytes());
      }
      assertThat(account).isNotNull();
      assertThat(account.path("buyingJourneys")).hasSize(1);
      assertThat(account.at("/buyingJourneys/0/journeyId").asText()).isEqualTo(id);
      assertThat(account.at("/buyingJourneys/0/shoppingBriefVersions").findValuesAsText("radiusMiles")).containsExactly("50", "100");
      assertThat(account.path("inventorySearchRuns")).hasSize(1);
      assertThat(account.at("/inventorySearchRuns/0/journeyId").asText()).isEqualTo(id);
      assertThat(account.at("/inventorySearchRuns/0/query").asText()).contains("27601");
      assertThat(account.at("/inventorySearchRuns/0/resultCount").asInt()).isEqualTo(5);

      assertThat(exchange(client, "POST", "/api/v1/account/deletion", Map.of(), session, null).status()).isEqualTo(202);
      // Access ends at once.
      assertThat(exchange(client, "GET", "/api/v1/journeys", null, session, null).status()).isEqualTo(401);
      var usage = app.context().getBean(com.dealanalyzer.operations.ProviderUsageStore.class);
      assertThatThrownBy(() -> usage.startInventory(accountId, id,
          new com.dealanalyzer.shopping.inventory.DeterministicFakeInventorySearchAdapter().provider(), query))
          .isInstanceOf(com.dealanalyzer.buyingcase.ResourceNotFoundException.class);
      app.context().getBean(AccountDeletionWorker.class).runOnce();
      assertThat(jdbc.queryForObject("select count(*) from buying_journeys where id = ?", Long.class, id)).isZero();
      assertThat(jdbc.queryForObject("select count(*) from shopping_brief_versions where journey_id = ?", Long.class, id)).isZero();
      assertThat(jdbc.queryForObject("select count(*) from provider_usage where journey_id = ?", Long.class, id)).isZero();
      assertThat(jdbc.queryForObject("select count(*) from provider_usage where journey_id = ?", Long.class, otherId)).isEqualTo(1);
      var receipt = exchange(client, "GET", "/api/v1/account/deletion", null, session, null).body();
      assertThat(receipt.path("state").asText()).isEqualTo("DELETED");
      // The other Buyer's journey is untouched.
      assertThat(exchange(client, "GET", "/api/v1/journeys", null, other, null).body()).hasSize(1);
    }
  }

  private HttpAcceptanceHarness.RunningApplication start(String database) {
    return new HttpAcceptanceHarness(directory).startWithConfiguration(database, false, EmailConfiguration.class, KEY,
        "--case.deletion.delay=PT1H");
  }

  private static String signIn(RestClient client, String email) throws Exception {
    HttpAcceptanceHarness.post(client, "/api/v1/account/sign-in-requests", Map.of("email", email), UUID.randomUUID().toString());
    String secret = links.getLast().split("#sign-in=")[1];
    return HttpAcceptanceHarness.post(client, "/api/v1/account/sign-in/verify", Map.of("token", secret))
        .path("sessionToken").asText();
  }

  record Result(int status, JsonNode body) {}

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

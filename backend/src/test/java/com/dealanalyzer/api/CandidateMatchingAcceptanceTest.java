package com.dealanalyzer.api;

import static org.assertj.core.api.Assertions.*;

import com.dealanalyzer.recovery.RecoveryEmail;
import com.dealanalyzer.shopping.matching.CandidateMatcher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** Acceptance scenario 16 through the HTTP API, with the deterministic inventory and VIN fixtures. */
class CandidateMatchingAcceptanceTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String KEY = "--document.storage.encryption-key=0123456789abcdef0123456789abcdef";
  private static final String SESSION = "Account-Session-Token";
  private static final String CIVIC_EX_A = "2HGFE2F55NH000008";
  private static final String CIVIC_EX_B = "2HGFE2F5XNH000005";
  static final CopyOnWriteArrayList<String> links = new CopyOnWriteArrayList<>();

  @TestConfiguration static class Configuration {
    @Bean @Primary RecoveryEmail candidateEmail() {
      return new RecoveryEmail() {
        public void send(String recipient, String link) { links.add(link); }
        @Override public void send(String recipient, String link, Purpose purpose) { links.add(link); }
      };
    }
  }

  @TempDir Path directory;

  @BeforeEach void clear() { links.clear(); }

  @Test void referenceTrimsRefineACivicBriefAndCandidatesExplainThemselves() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("candidates", false, Configuration.class, KEY,
        "--case.deletion.delay=PT1H")) {
      var client = app.client();
      String session = signIn(client, "buyer@example.com");
      var civic = BuyingJourneyAcceptanceTest.brief();
      civic.put("makeId", "honda"); civic.put("modelId", "honda-civic"); civic.put("trimIds", List.of("honda-civic-ex"));
      civic.put("mustHaveFeatures", List.of("apple-carplay"));
      String id = exchange(client, "POST", "/api/v1/journeys", civic, session, UUID.randomUUID().toString()).body().path("id").asText();
      String base = "/api/v1/journeys/" + id;

      // A Buyer Account session is required, and another Buyer cannot search this journey.
      assertThat(exchange(client, "POST", base + "/candidates", null, null, null).status()).isEqualTo(401);
      assertThat(exchange(client, "POST", base + "/candidates", null, signIn(client, "other@example.com"), null).status())
          .isEqualTo(404);

      // Two EX-trim Civic references; the Buyer marks their trim as mattering.
      var first = exchange(client, "POST", base + "/reference-vehicles", Map.of("basedOnVersion", 1, "vin", CIVIC_EX_A), session).body();
      var second = exchange(client, "POST", base + "/reference-vehicles", Map.of("basedOnVersion", 2, "vin", CIVIC_EX_B), session).body();
      assertThat(second.path("referenceVehicles")).hasSize(2);
      int version = 3;
      for (var vehicle : second.path("referenceVehicles")) {
        var marked = exchange(client, "PUT", base + "/reference-vehicles/" + vehicle.path("referenceId").asText() + "/relevance",
            Map.of("basedOnVersion", version, "relevantAttributes", List.of("TRIM")), session).body();
        version = marked.at("/brief/version").asInt();
      }
      assertThat(first.at("/referenceVehicles/0/vin").asText()).isEqualTo(CIVIC_EX_A);

      var results = exchange(client, "POST", base + "/candidates", null, session, null);
      assertThat(results.status()).isEqualTo(200);
      var body = results.body();
      assertThat(body.path("matcherVersion").asText()).isEqualTo(CandidateMatcher.VERSION);
      assertThat(body.path("briefVersion").asInt()).isEqualTo(version);
      assertThat(body.path("availability").asText()).isEqualTo("AVAILABLE");
      assertThat(body.path("notice").asText()).contains("not for sale");
      assertThat(body.path("ordering").asText()).isEqualTo(CandidateMatcher.ORDERING);
      var candidates = body.path("candidates");
      // The fixture: a match, a stale copy of it and a listing with no stated facts; the near miss is left out.
      assertThat(candidates.findValuesAsText("listingId")).containsExactly("match", "missing-vin", "stale", "missing-facts");
      var top = candidates.get(0);
      assertThat(top.path("title").asText()).isEqualTo("2024 Honda Civic EX");
      assertThat(top.path("matched").findValuesAsText("text")).contains("Matches your reference trim (EX).",
          "Trim EX is one of your trims.", "Lists Apple CarPlay.");
      assertThat(top.path("snapshot").path("source").asText()).isEqualTo("SYNTHETIC_INVENTORY");
      assertThat(top.path("snapshot").path("retrievedAt").asText()).isNotBlank();
      assertThat(top.path("snapshot").path("staleness").asText()).isEqualTo("CURRENT");
      assertThat(candidates.get(2).path("snapshot").path("staleness").asText()).isEqualTo("STALE");
      assertThat(candidates.get(3).path("unconfirmed").findValuesAsText("text"))
          .contains("The listing does not state its trim, so your reference trim (EX) is unconfirmed.");
      assertThat(body.path("excluded").findValuesAsText("listingId")).containsExactly("near-miss");
      assertThat(body.at("/excluded/0/missed").findValuesAsText("text")).anyMatch(text -> text.contains("over your 60,000-mile maximum"));

      // No score and no dealer identity anywhere in the response.
      String raw = body.toString();
      assertThat(raw).doesNotContainIgnoringCase("score").doesNotContainIgnoringCase("dealer")
          .doesNotContain("Synthetic Example Dealer");
      // The same brief gives the same candidates and wording.
      var again = exchange(client, "POST", base + "/candidates", null, session, null).body();
      assertThat(again.path("candidates").toString().replaceAll("\"(retrievedAt|observedAt)\":\"[^\"]+\"", ""))
          .isEqualTo(candidates.toString().replaceAll("\"(retrievedAt|observedAt)\":\"[^\"]+\"", ""));
    }
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

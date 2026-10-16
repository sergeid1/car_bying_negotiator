package com.dealanalyzer.api;

import static org.assertj.core.api.Assertions.*;

import com.dealanalyzer.ai.ModelGateway;
import com.dealanalyzer.ai.ModelHarness;
import com.dealanalyzer.ai.ModelPricing;
import com.dealanalyzer.ai.ModelProvider;
import com.dealanalyzer.ai.ModelSettings;
import com.dealanalyzer.ai.TotoAnswerTask;
import com.dealanalyzer.ai.TotoGuideLoader;
import com.dealanalyzer.operations.ProviderMeter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/** Ticket 120 through HTTP, the real usage store and database, with a local stand-in for the model vendor. */
@ExtendWith(OutputCaptureExtension.class)
class TotoAcceptanceTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String KEY = "--document.storage.encryption-key=0123456789abcdef0123456789abcdef";
  private static final ModelProvider PROVIDER = ModelProvider.ANTHROPIC;
  private static final String PASSAGE = "A doc fee is a dealer charge for preparing the sale paperwork.";
  private static final String QUESTION = "Private question sentinel about the doc fee";
  private static final String TURN = "Private turn sentinel ";

  enum Vendor { ANSWER, FAILURE, EXPENSIVE, HELD }
  static final AtomicReference<java.util.concurrent.CountDownLatch> hold = new AtomicReference<>();
  static final AtomicReference<Vendor> vendor = new AtomicReference<>(Vendor.ANSWER);
  static final AtomicInteger calls = new AtomicInteger();
  static final MutableClock clock = new MutableClock(Instant.now());

  static final class MutableClock extends Clock {
    private volatile Instant now;
    MutableClock(Instant now) { this.now = now; }
    void set(Instant instant) { now = instant; }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
  }

  /** An enabled harness on the real meter and store; only the vendor is replaced. */
  @TestConfiguration static class Configuration {
    @Bean @Primary TotoGuideLoader testGuide() {
      return () -> new TotoGuideLoader.Guide("test.v1", List.of(
          new TotoGuideLoader.Article("doc-fee", "Doc fee", PASSAGE, "?guide=doc-fee"),
          new TotoGuideLoader.Article("trade-in", "Trade-in value", "A trade-in is the car you sell to the dealer.")));
    }

    @Bean @Primary ModelHarness testHarness(ProviderMeter meter, ObjectMapper json) {
      return new ModelHarness(meter, new ModelSettings(true, PROVIDER, PROVIDER.defaultModel(), "test.v1", "test.v1"),
          request -> {
            calls.incrementAndGet();
            return switch (vendor.get()) {
              case FAILURE -> throw new IllegalStateException("vendor down");
              case HELD -> {
                try { hold.get().await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                yield answer(request.modelId(), 100);
              }
              case ANSWER, EXPENSIVE -> answer(request.modelId(), vendor.get() == Vendor.EXPENSIVE ? 225_000 : 100);
            };
          }, "test-terms", json);
    }

    private static ModelGateway.ModelExchange answer(String model, long inputTokens) {
      return new ModelGateway.ModelExchange(ModelGateway.Outcome.COMPLETED, model,
          "{\"kind\":\"ANSWER\",\"paragraphs\":[{\"text\":\"" + PASSAGE + "\",\"article_ids\":[\"doc-fee\"]}],"
              + "\"decline_reason\":null}", inputTokens, 20);
    }

    @Bean @Primary Clock testClock() { return clock; }
  }

  @Import({Configuration.class, BuyerAccountAcceptanceTest.EmailConfiguration.class})
  @TestConfiguration static class WithEmail {}

  @TempDir Path directory;

  @BeforeEach void reset() {
    vendor.set(Vendor.ANSWER);
    calls.set(0);
    clock.set(Instant.now());
    BuyerAccountAcceptanceTest.sent.clear();
  }

  @Test void limitsCapsAndFailuresAnswerWithoutStoringText(CapturedOutput logs) throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("toto", false, WithEmail.class, KEY,
        "--toto.enabled=true", "--toto.account-daily-questions=2", "--toto.client-daily-questions=3")) {
      var client = app.client();
      var jdbc = new JdbcTemplate(app.context().getBean(javax.sql.DataSource.class));
      assertThat(get(client).path("available").asBoolean()).isTrue();

      // Request limits refuse with fixed wording before any cap is counted or the vendor is called.
      var turns = new ArrayList<Map<String, String>>();
      for (int i = 0; i < 6; i++) turns.add(Map.of("role", i % 2 == 0 ? "BUYER" : "TOTO", "text", TURN + "x".repeat(1_500 - TURN.length())));
      refused(ask(client, "", List.of(), null), 400, "TOTO_QUESTION_INVALID");
      refused(ask(client, "   ", List.of(), null), 400, "TOTO_QUESTION_INVALID");
      refused(ask(client, QUESTION + "q".repeat(1_001 - QUESTION.length()), List.of(), null), 400, "TOTO_QUESTION_INVALID");
      var seven = new ArrayList<>(turns);
      seven.add(Map.of("role", "BUYER", "text", "One more"));
      refused(ask(client, QUESTION, seven, null), 400, "TOTO_TURNS_INVALID");
      refused(ask(client, QUESTION, List.of(Map.of("role", "BUYER", "text", TURN + "x".repeat(1_501 - TURN.length()))), null),
          400, "TOTO_TURNS_INVALID");
      refused(ask(client, QUESTION, List.of(Map.of("role", "DEALER", "text", TURN)), null), 400, "TOTO_TURNS_INVALID");
      refused(ask(client, QUESTION, List.of(Map.of("role", "BUYER", "text", "")), null), 400, "TOTO_TURNS_INVALID");
      var unreadable = send(client, "{\"question\": \"" + QUESTION + "\", \"turns\": [", null);
      refused(unreadable, 400, "TOTO_QUESTION_INVALID");
      assertThat(unreadable.body().toString()).doesNotContain("sentinel");
      assertThat(calls).hasValue(0);

      // A client without an account: the longest permitted request is answered in the agreed shape.
      var answer = ask(client, QUESTION + "q".repeat(1_000 - QUESTION.length()), turns, null);
      assertThat(answer.status()).isEqualTo(200);
      assertThat(answer.body().path("kind").asText()).isEqualTo("ANSWER");
      assertThat(answer.body().at("/paragraphs/0/text").asText()).isEqualTo(PASSAGE);
      assertThat(answer.body().at("/paragraphs/0/citations/0")).isEqualTo(JSON.readTree(
          "{\"articleId\":\"doc-fee\",\"title\":\"Doc fee\",\"link\":\"?guide=doc-fee\"}"));
      assertThat(answer.body().path("message").isNull()).isTrue();
      assertThat(answer.body().path("suggestions").size()).isZero();
      assertThat(answer.body().has("provenance")).isFalse();

      // A provider failure is the fixed fallback with title-matched guides, and still counts.
      vendor.set(Vendor.FAILURE);
      var fallback = ask(client, QUESTION, List.of(), null);
      assertThat(fallback.status()).isEqualTo(200);
      assertThat(fallback.body().path("kind").asText()).isEqualTo("FALLBACK");
      assertThat(fallback.body().path("message").asText()).isEqualTo(TotoAnswerTask.FALLBACK);
      assertThat(fallback.body().at("/suggestions/0/articleId").asText()).isEqualTo("doc-fee");
      assertThat(fallback.body().path("paragraphs").size()).isZero();
      vendor.set(Vendor.ANSWER);

      // The third question is the client's last today; the fourth is refused without a vendor call.
      assertThat(ask(client, QUESTION, List.of(), null).status()).isEqualTo(200);
      assertThat(calls).hasValue(3);
      var resting = ask(client, QUESTION, List.of(), null);
      refused(resting, 429, "TOTO_RESTING");
      assertThat(resting.body().path("detail").asText()).isEqualTo(TotoService.RESTING);
      assertThat(calls).hasValue(3);

      // A signed-in account has its own cap, from the same address, counted from its usage rows.
      String session = signIn(client, "toto@example.com");
      String accountId = jdbc.queryForObject("select id from buyer_accounts", String.class);
      assertThat(ask(client, QUESTION, List.of(), session).status()).isEqualTo(200);
      assertThat(ask(client, QUESTION, turns.subList(0, 2), session).status()).isEqualTo(200);
      refused(ask(client, QUESTION, List.of(), session), 429, "TOTO_RESTING");
      assertThat(calls).hasValue(5);
      assertThat(jdbc.queryForObject("select count(*) from provider_usage where account_id = ? and operation = 'GUIDE_ANSWER'",
          Long.class, accountId)).isEqualTo(2);
      // An ended session asks as a client without an account, which is resting too.
      refused(ask(client, QUESTION, List.of(), "ended-session"), 429, "TOTO_RESTING");

      // Every cap resets the next day.
      clock.set(clock.instant().plus(Duration.ofDays(1)));
      assertThat(ask(client, QUESTION, List.of(), null).status()).isEqualTo(200);
      assertThat(ask(client, QUESTION, List.of(), session).status()).isEqualTo(200);
      assertThat(calls).hasValue(7);

      // Only counts, tokens, outcome and provenance are kept; no question, turn or answer text anywhere.
      assertThat(jdbc.queryForObject("select count(*) from provider_usage where operation = 'GUIDE_ANSWER' and case_id is null",
          Long.class)).isEqualTo(7);
      assertThat(jdbc.queryForObject("select count(*) from provider_usage where status = 'PROVIDER_FAILURE' and cost_usd is null",
          Long.class)).isEqualTo(1);
      assertThat(jdbc.queryForList("select * from provider_usage").toString())
          .doesNotContain("sentinel", PASSAGE, "doc fee");
      assertThat(logs.getAll()).doesNotContain("sentinel", PASSAGE);
    }
  }

  @Test void theSpendingCeilingCountsKnownUnpricedAndFailedCallsAndResetsTheNextDay() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("toto-ceiling", false, Configuration.class, KEY,
        "--toto.enabled=true", "--toto.daily-spending-ceiling-usd=1.00", "--toto.client-daily-questions=1000")) {
      var client = app.client();
      var jdbc = new JdbcTemplate(app.context().getBean(javax.sql.DataSource.class));
      BigDecimal worst = app.context().getBean(TotoService.class).worstCallUsd();
      // Large enough that one call fits under $1 and a priced $0.90 call leaves no room for another.
      assertThat(worst).isBetween(new BigDecimal("0.10"), new BigDecimal("0.50"));
      assertThat(ModelPricing.costUsd(PROVIDER, PROVIDER.defaultModel(), 225_000, 20).orElseThrow())
          .isEqualByComparingTo("0.9004");

      // Calls with no known cost count at the worst-case price; a vendor failure reports none.
      vendor.set(Vendor.FAILURE);
      int fits = BigDecimal.ONE.divide(worst, 0, java.math.RoundingMode.DOWN).intValueExact();
      for (int i = 0; i < fits; i++) assertThat(ask(client, QUESTION, List.of(), null).body().path("kind").asText()).isEqualTo("FALLBACK");
      refused(ask(client, QUESTION, List.of(), null), 429, "TOTO_RESTING");
      assertThat(get(client).path("available").asBoolean()).isFalse();
      assertThat(calls).hasValue(fits);

      // The next day Toto is available again.
      clock.set(clock.instant().plus(Duration.ofDays(1)));
      assertThat(get(client).path("available").asBoolean()).isTrue();

      // Known cost: back on today's clock with the earlier rows a day old, one $0.90 call reaches the ceiling.
      clock.set(Instant.now());
      daysPass(jdbc);
      vendor.set(Vendor.EXPENSIVE);
      assertThat(ask(client, QUESTION, List.of(), null).status()).isEqualTo(200);
      refused(ask(client, QUESTION, List.of(), null), 429, "TOTO_RESTING");
      assertThat(calls).hasValue(fits + 1);

      // A call still in flight counts too: while one is held, fewer than the usual number fit beside it.
      daysPass(jdbc);
      var held = new java.util.concurrent.CountDownLatch(1);
      vendor.set(Vendor.HELD);
      hold.set(held);
      var first = java.util.concurrent.CompletableFuture.supplyAsync(() -> ask(client, QUESTION, List.of(), null));
      long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
      while (calls.get() < fits + 2 && System.nanoTime() < deadline) Thread.onSpinWait();
      assertThat(calls).hasValue(fits + 2);
      vendor.set(Vendor.FAILURE);
      int beside = 0;
      while (ask(client, QUESTION, List.of(), null).status() == 200) beside++;
      assertThat(beside).isLessThan(fits);
      held.countDown();
      assertThat(first.get().status()).isEqualTo(200);
    }
  }

  /** Moves every usage row back a day, as if the day had passed. */
  private static void daysPass(JdbcTemplate jdbc) {
    jdbc.update("update provider_usage set created_at = created_at - interval '1' day");
  }

  @Test void disabledTotoIsUnavailableAndCallsNothing() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("toto-disabled", false, Configuration.class, KEY)) {
      var client = app.client();
      assertThat(get(client).path("available").asBoolean()).isFalse();
      refused(ask(client, QUESTION, List.of(), null), 503, "TOTO_UNAVAILABLE");
      // Validation still comes first, so a bad request is told what is wrong with it.
      refused(ask(client, "", List.of(), null), 400, "TOTO_QUESTION_INVALID");
      assertThat(calls).hasValue(0);
      var jdbc = new JdbcTemplate(app.context().getBean(javax.sql.DataSource.class));
      assertThat(jdbc.queryForObject("select count(*) from provider_usage", Long.class)).isZero();
    }
  }

  record Result(int status, JsonNode body) {}

  private static void refused(Result result, int status, String code) {
    assertThat(result.status()).as(result.toString()).isEqualTo(status);
    assertThat(result.body().path("code").asText()).isEqualTo(code);
    assertThat(result.body().path("detail").asText()).isNotBlank().doesNotContain("sentinel");
  }

  private static JsonNode get(RestClient client) throws Exception {
    return JSON.readTree(client.get().uri("/api/v1/toto").retrieve().body(String.class));
  }

  private static Result ask(RestClient client, String question, List<Map<String, String>> turns, String session) {
    try {
      return send(client, JSON.writeValueAsString(Map.of("question", question, "turns", turns)), session);
    } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static Result send(RestClient client, String body, String session) {
    var request = client.post().uri("/api/v1/toto/answers").contentType(MediaType.APPLICATION_JSON).body(body);
    if (session != null) request.header("Cookie", AccountSessionTestSupport.cookie(session));
    return request.exchange((ignored, response) -> {
      String text = new String(response.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      return new Result(response.getStatusCode().value(), text.isEmpty() ? JSON.nullNode() : JSON.readTree(text));
    });
  }

  private static String signIn(RestClient client, String email) throws Exception {
    HttpAcceptanceHarness.post(client, "/api/v1/account/sign-in-requests", Map.of("email", email));
    return AccountSessionTestSupport.verify(client,
        BuyerAccountAcceptanceTest.sent.getLast().link().split("#sign-in=")[1]);
  }
}

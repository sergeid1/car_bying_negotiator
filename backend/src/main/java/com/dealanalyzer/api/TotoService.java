package com.dealanalyzer.api;

import com.dealanalyzer.ai.ModelHarness;
import com.dealanalyzer.ai.ModelPricing;
import com.dealanalyzer.ai.ModelRolloutGate;
import com.dealanalyzer.ai.ModelTask;
import com.dealanalyzer.ai.TotoAnswerTask;
import com.dealanalyzer.ai.TotoGuideLoader;
import com.dealanalyzer.operations.ModelUsageScope;
import com.dealanalyzer.operations.ProviderUsageStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Toto's availability and caps. Nothing here sees question or answer text except to pass it to the
 * answerer; usage rows hold counts, tokens, cost, outcome and provenance only. Days are UTC days.
 */
@Service
public class TotoService {
  public static final String RESTING = "Toto is resting for now. Try again later; the guides are here meanwhile.";
  static final int QUESTION_LIMIT = 1_000;
  static final int TURN_LIMIT = 1_500;
  static final int TURNS = 6;
  /** Request framing and role labels around the untrusted inputs, in tokens; generous on purpose. */
  private static final long FRAMING_TOKENS = 4_096;

  private final boolean enabled;
  private final int accountDailyQuestions;
  private final BigDecimal dailySpendingCeilingUsd;
  private final ModelHarness harness;
  private final TotoAnswerTask.Answerer answerer;
  private final TotoGuideLoader.Guide guide;
  private final ProviderUsageStore usage;
  private final Clock clock;
  private final ApiRateLimitFilter.PeerQuota clients;
  /** Worst-case cost of one call, or null when the configured model has no known price. */
  private final BigDecimal worstCallUsd;
  private int inFlight;
  private final Map<String, Integer> inFlightByAccount = new HashMap<>();

  TotoService(@Value("${toto.enabled:false}") boolean enabled,
      @Value("${toto.account-daily-questions:30}") int accountDailyQuestions,
      @Value("${toto.client-daily-questions:10}") int clientDailyQuestions,
      @Value("${toto.max-clients:10000}") int maxClients,
      @Value("${toto.daily-spending-ceiling-usd:20.00}") BigDecimal dailySpendingCeilingUsd,
      ModelHarness harness, ModelRolloutGate gate, TotoAnswerTask.Answerer answerer, TotoGuideLoader.Guide guide,
      ModelTask<TotoAnswerTask.Output> task, ProviderUsageStore usage, ObjectMapper json,
      ObjectProvider<Clock> clock) {
    if (accountDailyQuestions < 1 || dailySpendingCeilingUsd.signum() <= 0)
      throw new IllegalArgumentException("Toto caps must be positive");
    this.enabled = enabled;
    this.accountDailyQuestions = accountDailyQuestions;
    this.dailySpendingCeilingUsd = dailySpendingCeilingUsd;
    this.harness = harness;
    this.answerer = answerer;
    this.guide = guide;
    this.usage = usage;
    this.clock = clock.getIfAvailable(Clock::systemUTC);
    // The existing per-client quota, over one day and on this service's clock.
    this.clients = new ApiRateLimitFilter.PeerQuota(clientDailyQuestions, Duration.ofDays(1), maxClients);
    this.worstCallUsd = worstCallUsd(gate, task, json);
  }

  /**
   * An upper bound for one call: every UTF-16 unit of the instructions, schema and the largest
   * permitted question and turns is at most three UTF-8 bytes, and no tokenizer emits more tokens
   * than bytes. Cached input is priced at the full rate, as {@link ModelPricing} does.
   */
  private static BigDecimal worstCallUsd(ModelRolloutGate gate, ModelTask<TotoAnswerTask.Output> task, ObjectMapper json) {
    long input;
    try {
      input = task.instructions().getBytes(StandardCharsets.UTF_8).length
          + json.writeValueAsBytes(task.outputSchema()).length
          + 3L * (QUESTION_LIMIT + (long) TURNS * TURN_LIMIT) + FRAMING_TOKENS;
    } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
      throw new IllegalStateException("Toto schema is not serializable");
    }
    var settings = gate.settings();
    return ModelPricing.costUsd(settings.provider(), settings.modelId(), input, task.maxOutputTokens()).orElse(null);
  }

  BigDecimal worstCallUsd() { return worstCallUsd; }

  /** Whether a question could be answered now; the client hides Toto otherwise. */
  public boolean available() {
    if (!configured()) return false;
    synchronized (this) {
      return !ceilingReached(today());
    }
  }

  private boolean configured() {
    return enabled && harness.available() && !guide.articles().isEmpty() && worstCallUsd != null;
  }

  public enum Refusal { UNAVAILABLE, RESTING }
  public static final class Refused extends RuntimeException {
    final Refusal refusal;
    Refused(Refusal refusal) { super(refusal.name(), null, false, false); this.refusal = refusal; }
  }

  /**
   * Answers one question for an account or, with a null account, for a client address. Counts are
   * checked and the call reserved under one lock so concurrent questions cannot pass a cap together.
   */
  public TotoAnswerTask.Answer answer(String accountId, String client, String question, List<TotoAnswerTask.Turn> turns) {
    if (!configured()) throw new Refused(Refusal.UNAVAILABLE);
    synchronized (this) {
      Instant[] day = today();
      if (ceilingReached(day)) throw new Refused(Refusal.RESTING);
      if (accountId != null) {
        long asked = usage.guideAnswerCount(accountId, day[0], day[1]) + inFlightByAccount.getOrDefault(accountId, 0);
        if (asked >= accountDailyQuestions) throw new Refused(Refusal.RESTING);
      } else if (clients.acquire(client, nanos(clock.instant())) > 0) {
        throw new Refused(Refusal.RESTING);
      }
      inFlight++;
      if (accountId != null) inFlightByAccount.merge(accountId, 1, Integer::sum);
    }
    try {
      return answerer.answer(ModelUsageScope.guideAnswer(accountId), question, turns);
    } finally {
      synchronized (this) {
        inFlight--;
        if (accountId != null) inFlightByAccount.computeIfPresent(accountId, (key, count) -> count == 1 ? null : count - 1);
      }
    }
  }

  /**
   * Known cost, plus every row without a known cost and every call admitted here at the worst-case
   * price. A call admitted here is counted twice once its row exists; that overstates, never understates.
   */
  private boolean ceilingReached(Instant[] day) {
    BigDecimal spent = usage.guideAnswerCostUsd(day[0], day[1])
        .add(worstCallUsd.multiply(BigDecimal.valueOf(usage.guideAnswersWithoutCost(day[0], day[1]) + inFlight)));
    return spent.add(worstCallUsd).compareTo(dailySpendingCeilingUsd) > 0;
  }

  private Instant[] today() {
    Instant start = clock.instant().atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS).toInstant();
    return new Instant[] {start, start.plus(1, ChronoUnit.DAYS)};
  }

  /** PeerQuota measures windows in nanoseconds; derive them from the clock so a test can advance days. */
  private static long nanos(Instant instant) {
    return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
  }
}

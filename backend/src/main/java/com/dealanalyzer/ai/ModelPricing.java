package com.dealanalyzer.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;

/**
 * Code-owned list prices in USD per million tokens, keyed by provider and model. A pair without an
 * entry has unknown cost, which is recorded as {@code null} rather than an invented zero, and the
 * rollout gate refuses it. Cached input is counted at the full input rate: the harness does not
 * request caching, so this can only overstate spend.
 */
public final class ModelPricing {
  private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
  private record Rates(BigDecimal input, BigDecimal output) {}
  private static final Map<ModelProvider, Map<String, Rates>> PER_MILLION = Map.of(
      ModelProvider.ANTHROPIC, Map.of("claude-opus-5", new Rates(new BigDecimal("5.00"), new BigDecimal("25.00"))),
      // developers.openai.com/api/docs/models/gpt-6-astra, checked 2026-09-30.
      ModelProvider.OPENAI, Map.of("gpt-6-astra", new Rates(new BigDecimal("10.00"), new BigDecimal("50.00"))));

  private ModelPricing() {}

  public static boolean known(ModelProvider provider, String modelId) {
    return PER_MILLION.getOrDefault(provider, Map.of()).containsKey(modelId);
  }

  public static Optional<BigDecimal> costUsd(ModelProvider provider, String modelId, long inputTokens, long outputTokens) {
    Rates rates = PER_MILLION.getOrDefault(provider, Map.of()).get(modelId);
    if (rates == null) return Optional.empty();
    return Optional.of(rates.input().multiply(BigDecimal.valueOf(inputTokens))
        .add(rates.output().multiply(BigDecimal.valueOf(outputTokens)))
        .divide(MILLION, 8, RoundingMode.UP));
  }
}

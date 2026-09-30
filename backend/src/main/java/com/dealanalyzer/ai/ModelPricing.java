package com.dealanalyzer.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;

/**
 * Code-owned list prices in USD per million tokens. A model without an entry has unknown cost, which
 * is recorded as {@code null} rather than an invented zero, and the rollout gate refuses it.
 * Cache writes and reads are counted at the input rate: the harness does not request caching, so this
 * can only overstate spend.
 */
public final class ModelPricing {
  private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
  private static final Map<String, BigDecimal[]> PER_MILLION = Map.of(
      "claude-opus-5", new BigDecimal[] {new BigDecimal("5.00"), new BigDecimal("25.00")});

  private ModelPricing() {}

  public static boolean known(String modelId) {
    return PER_MILLION.containsKey(modelId);
  }

  public static Optional<BigDecimal> costUsd(String modelId, long inputTokens, long outputTokens) {
    BigDecimal[] rates = PER_MILLION.get(modelId);
    if (rates == null) return Optional.empty();
    return Optional.of(rates[0].multiply(BigDecimal.valueOf(inputTokens))
        .add(rates[1].multiply(BigDecimal.valueOf(outputTokens)))
        .divide(MILLION, 8, RoundingMode.UP));
  }
}

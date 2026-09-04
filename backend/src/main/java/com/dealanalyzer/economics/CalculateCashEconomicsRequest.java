package com.dealanalyzer.economics;

import java.util.Set;

public record CalculateCashEconomicsRequest(Set<String> verifiedRebateEvidenceIds) {
  public CalculateCashEconomicsRequest {
    verifiedRebateEvidenceIds = verifiedRebateEvidenceIds == null
        ? Set.of() : Set.copyOf(verifiedRebateEvidenceIds);
  }

  public static CalculateCashEconomicsRequest empty() {
    return new CalculateCashEconomicsRequest(Set.of());
  }
}

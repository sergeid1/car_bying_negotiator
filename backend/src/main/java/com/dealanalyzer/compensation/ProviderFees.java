package com.dealanalyzer.compensation;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** Code-owned commercial terms. Never pass this catalog or its entries to Buyer result code. */
public record ProviderFees(String version, LocalDate effectiveFrom, List<Fee> fees) {
  public enum Source {
    INSURER, DIRECT_LENDER, LICENSED_COMPARISON_PARTNER,
    DEALER, DEALER_GROUP, DEALER_ARRANGED_FINANCING, DEALER_RESERVE, DESK_PRODUCT
  }

  /** Per completed quote or pre-approval fee, in USD minor units. Zero is a non-paying provider. */
  public record Fee(String providerId, Source source, long amountMinor) {
    public Fee {
      if (providerId == null || providerId.isBlank() || amountMinor < 0)
        throw new IllegalArgumentException("Provider identity and nonnegative fee required");
      Objects.requireNonNull(source, "Provider source required");
      switch (source) {
        case INSURER, DIRECT_LENDER, LICENSED_COMPARISON_PARTNER -> { }
        default -> throw new IllegalArgumentException("Dealer-related compensation is prohibited");
      }
    }
  }

  /** No signed commercial terms yet; do not invent live provider fees. */
  public static final ProviderFees CURRENT = new ProviderFees("provider-fees-v1", LocalDate.of(2026, 10, 15), List.of());

  public ProviderFees {
    if (version == null || version.isBlank() || effectiveFrom == null || fees == null)
      throw new IllegalArgumentException("Version, effective date and fees required");
    fees = List.copyOf(fees);
    if (fees.stream().map(Fee::providerId).distinct().count() != fees.size())
      throw new IllegalArgumentException("Duplicate provider fee");
  }
}

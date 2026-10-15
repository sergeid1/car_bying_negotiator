package com.dealanalyzer.compensation;

import static org.assertj.core.api.Assertions.*;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProviderFeesTest {
  @Test void dealerMoneyCannotBeRegisteredEvenAtZero() {
    for (var source : List.of(ProviderFees.Source.DEALER, ProviderFees.Source.DEALER_GROUP,
        ProviderFees.Source.DEALER_ARRANGED_FINANCING, ProviderFees.Source.DEALER_RESERVE, ProviderFees.Source.DESK_PRODUCT))
      for (long amount : List.of(0L, 1000L))
        assertThatIllegalArgumentException().isThrownBy(() -> new ProviderFees.Fee("provider", source, amount));
  }

  @Test void onlyIndependentSourcesCanHaveVersionedDatedFees() {
    for (var source : List.of(ProviderFees.Source.INSURER, ProviderFees.Source.DIRECT_LENDER,
        ProviderFees.Source.LICENSED_COMPARISON_PARTNER)) {
      var fee = new ProviderFees.Fee("provider", source, 100);
      var catalog = new ProviderFees("v2", LocalDate.of(2026, 10, 16), List.of(fee));
      assertThat(catalog.fees()).containsExactly(fee);
      assertThatIllegalArgumentException().isThrownBy(() -> new ProviderFees("v2", catalog.effectiveFrom(), List.of(fee, fee)));
    }
    assertThat(ProviderFees.CURRENT.version()).isNotBlank();
    assertThat(ProviderFees.CURRENT.effectiveFrom()).isEqualTo(LocalDate.of(2026, 10, 15));
    assertThatIllegalArgumentException().isThrownBy(() -> new ProviderFees.Fee("provider", ProviderFees.Source.INSURER, -1));
  }
}

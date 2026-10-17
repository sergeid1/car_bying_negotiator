package com.dealanalyzer.economics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dealanalyzer.economics.CashDealEconomics.LineType;
import com.dealanalyzer.economics.CashDealEconomics.TaxRules;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Ticket 137: non-taxable charge terms match a separately stated line's label at a word start. */
class TaxRulesTest {
  private final TaxRules rules = new TaxRules("Synthetic tax", Map.of(LineType.DEALER_FEE, List.of("doc"),
      LineType.ADD_ON, List.of("service contract", "gap")), false, null);

  @Test void termsMatchAtAWordStartWithoutRegardToCase() {
    assertThat(rules.nonTaxableTerm(LineType.DEALER_FEE, "Dealer DOC fee")).isEqualTo("doc");
    assertThat(rules.nonTaxableTerm(LineType.DEALER_FEE, "Documentary fee")).isEqualTo("doc");
    assertThat(rules.nonTaxableTerm(LineType.DEALER_FEE, "Paddock fee")).isNull();
    assertThat(rules.nonTaxableTerm(LineType.ADD_ON, "GAP waiver")).isEqualTo("gap");
    assertThat(rules.nonTaxableTerm(LineType.ADD_ON, "Vehicle Service Contract (60 mo)")).isEqualTo("service contract");
    assertThat(rules.nonTaxableTerm(LineType.ADD_ON, "Window tint")).isNull();
    assertThat(rules.nonTaxableTerm(LineType.ADD_ON, null)).isNull();
  }

  @Test void termsApplyOnlyToTheirOwnLineType() {
    assertThat(rules.nonTaxableTerm(LineType.ADD_ON, "Doc fee")).isNull();
    assertThat(TaxRules.NONE.nonTaxableTerm(LineType.DEALER_FEE, "Doc fee")).isNull();
  }

  @Test void theVehiclePriceAndBlankTermsCannotBeExcluded() {
    assertThatThrownBy(() -> new TaxRules(null, Map.of(LineType.SELLING_PRICE, List.of("price")), false, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TaxRules(null, Map.of(LineType.DEALER_FEE, List.of(" ")), false, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TaxRules(null, Map.of(LineType.DEALER_FEE, List.of()), false, null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

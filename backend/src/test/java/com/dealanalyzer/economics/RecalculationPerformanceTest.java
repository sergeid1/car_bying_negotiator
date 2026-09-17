package com.dealanalyzer.economics;

import static com.dealanalyzer.economics.CashDealEconomics.*;
import static org.assertj.core.api.Assertions.assertThat;
import java.math.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RecalculationPerformanceTest {
  @Test void deterministicRecalculationMeetsP95Budget() throws Exception {
    var usd = Currency.getInstance("USD");
    var offer = new Offer("performance", 1, usd, List.of(new LineItem("page-2", "Selling price",
        new Money(new BigDecimal("100.50"), usd), LineType.SELLING_PRICE, TaxTreatment.UNKNOWN,
        Eligibility.NOT_APPLICABLE)), new Money(new BigDecimal("109.00"), usd), "page-2");
    var policy = new Policy("performance.v1", "US-NC", usd, new BigDecimal("0.0725"),
        Set.of(LineType.SELLING_PRICE), false, 2, RoundingMode.HALF_UP, new BigDecimal("0.50"));
    var calculator = new CashDealEconomicsCalculator();
    for (int i = 0; i < 100; i++) calculator.calculate(offer, policy);
    long[] elapsed = new long[1000];
    for (int i = 0; i < elapsed.length; i++) {
      long start = System.nanoTime();
      var result = calculator.calculate(offer, policy);
      elapsed[i] = System.nanoTime() - start;
      assertThat(result.outTheDoorTotal().minorUnits()).isEqualTo(10779);
      assertThat(result.reconciliation().discrepancy().minorUnits()).isEqualTo(121);
    }
    Arrays.sort(elapsed);
    double p95 = elapsed[949] / 1_000_000.0;
    Files.createDirectories(Path.of("target/hardening"));
    Files.writeString(Path.of("target/hardening/recalculation.json"),
        "{\"scope\":\"pure cash economics, one line, local warm JVM\",\"samples\":1000,\"warmup\":100,\"p95Ms\":" + p95 + ",\"targetMs\":500}");
    assertThat(p95).isLessThan(500);
  }
}

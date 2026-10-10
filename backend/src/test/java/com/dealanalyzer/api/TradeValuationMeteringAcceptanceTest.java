package com.dealanalyzer.api;

import static com.dealanalyzer.api.HttpAcceptanceHarness.*;
import static org.assertj.core.api.Assertions.*;
import com.dealanalyzer.operations.ProviderMeter;
import com.dealanalyzer.trade.TradeService.Condition;
import com.dealanalyzer.trade.valuation.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

/** Exercises real migrations and ProviderUsageStore transactions; no valuation HTTP endpoint needed. */
class TradeValuationMeteringAcceptanceTest {
  @TempDir Path directory;
  @Test void eachCaseScopedCallPersistsProviderLicenceResultLatencyAndCost() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).start("trade-valuation-meter", false)) {
      var created = post(app.client(), "/api/v1/cases", CasePortabilityAcceptanceTest.buyingCase());
      String id = created.at("/case/id").asText();
      var vehicle = new TradeVehicle("1HGCM82633A004352", 60000, Condition.GOOD, "EX");
      var result = app.context().getBean(TradeValuationService.class).value(id, vehicle, "27601");
      assertThat(result.range().typicalMinor()).isEqualTo(1600000);
      var jdbc = app.context().getBean(JdbcTemplate.class);
      var row = jdbc.queryForMap("select * from provider_usage where case_id = ? and operation = 'TRADE_VALUATION'", id);
      assertThat(row.get("provider")).isEqualTo(result.provider().name());
      assertThat(row.get("provider_version")).isEqualTo(result.provider().version());
      assertThat(row.get("license")).isEqualTo(result.provider().licenceMetadata());
      assertThat(row.get("result_count")).isEqualTo(1);
      assertThat(row.get("inventory_availability")).isEqualTo("AVAILABLE");
      assertThat(row.get("status")).isEqualTo("SUCCESS");
      assertThat(((Number) row.get("latency_millis")).longValue()).isNotNegative();
      assertThat((BigDecimal) row.get("cost_usd")).isZero();
      assertThat(row.get("retrieved_at")).isNotNull(); assertThat(row.get("finished_at")).isNotNull();
      assertThat(row.get("query_json")).isNull();

      var provider = new TradeValuationAdapter.Provider("LICENSED_TEST", "v1", "TEST_EXECUTED_TERMS", false);
      var unavailable = TradeValuationContract.unavailable(provider, TradeValuationAdapter.Reason.RATE_LIMITED,
          Instant.now(), new BigDecimal("0.125"));
      var metered = app.context().getBean(ProviderMeter.class).callTradeValuation(id, provider, vehicle, () -> unavailable);
      assertThat(metered.range()).isNull();
      var outage = jdbc.queryForMap("select * from provider_usage where case_id = ? and provider = 'LICENSED_TEST'", id);
      assertThat(outage.get("provider")).isEqualTo(provider.name());
      assertThat(outage.get("license")).isEqualTo(provider.licenceMetadata());
      assertThat(outage.get("status")).isEqualTo("PROVIDER_UNAVAILABLE");
      assertThat(outage.get("result_count")).isEqualTo(0);
      assertThat(outage.get("inventory_availability")).isEqualTo("RATE_LIMITED");
      assertThat((BigDecimal) outage.get("cost_usd")).isEqualByComparingTo("0.125");
      assertThat(((Number) outage.get("latency_millis")).longValue()).isNotNegative();
      assertThat(jdbc.queryForObject("select count(*) from provider_usage where case_id = ?", Integer.class, id)).isEqualTo(2);

      var calls = new java.util.concurrent.atomic.AtomicBoolean();
      assertThatThrownBy(() -> app.context().getBean(ProviderMeter.class).callTradeValuation("missing-case", provider,
          vehicle, () -> { calls.set(true); return unavailable; }))
          .isInstanceOf(com.dealanalyzer.buyingcase.ResourceNotFoundException.class);
      assertThat(calls).isFalse();
    }
  }
}

package com.dealanalyzer.trade.valuation;

import static org.assertj.core.api.Assertions.*;
import static com.dealanalyzer.trade.valuation.TradeValuationAdapter.*;
import com.dealanalyzer.trade.TradeService.Condition;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Every replacement adapter runs these recorded fixture scenarios without paid endpoints. */
public abstract class TradeValuationAdapterConformance {
  public enum Fixture { NORMAL, WIDE_RANGE, UNKNOWN_TRIM, HIGH_MILEAGE, STALE, UNAVAILABLE, TIMED_OUT, RATE_LIMITED }
  protected abstract TradeValuationAdapter adapter(Fixture fixture);
  protected abstract Instant now();
  protected TradeVehicle vehicle(Fixture fixture) {
    return new TradeVehicle(" 1hgcm82633a004352 ", fixture == Fixture.HIGH_MILEAGE ? 180000 : 60000,
        Condition.GOOD, fixture == Fixture.UNKNOWN_TRIM ? null : " EX ");
  }
  private Result result(Fixture fixture) {
    var adapter = adapter(fixture);
    var vehicle = vehicle(fixture);
    var result = adapter.value(vehicle, "27601");
    assertThat(TradeValuationContract.failure(result, adapter.provider(), vehicle, now())).isNull();
    assertThat(result.provider().name()).isNotBlank();
    assertThat(result.provider().licenceMetadata()).isNotBlank();
    assertThat(result.retrievedAt()).isNotNull();
    return result;
  }
  @Test void normalizationAndMinorUnitRangePreserveSourceAndCondition() {
    var vehicle = vehicle(Fixture.NORMAL);
    assertThat(vehicle.vin()).isEqualTo("1HGCM82633A004352");
    assertThat(vehicle.trim()).isEqualTo("EX");
    var result = result(Fixture.NORMAL);
    assertThat(result.availability()).isEqualTo(Availability.AVAILABLE);
    assertThat(result.range().currency()).isEqualTo("USD");
    assertThat(result.range().lowMinor()).isNotNegative().isLessThanOrEqualTo(result.range().typicalMinor());
    assertThat(result.range().highMinor()).isGreaterThanOrEqualTo(result.range().typicalMinor());
    assertThat(result.conditionAssumption()).isEqualTo(vehicle.condition());
    assertThat(result.observedAt()).isBetween(now().minus(TradeValuationContract.MAX_AGE), result.retrievedAt());
  }
  @Test void wideUnknownTrimAndHighMileageAreUsableWithoutInventingTrim() {
    for (var fixture : List.of(Fixture.WIDE_RANGE, Fixture.UNKNOWN_TRIM, Fixture.HIGH_MILEAGE))
      assertThat(result(fixture).availability()).isEqualTo(Availability.AVAILABLE);
    assertThat(vehicle(Fixture.UNKNOWN_TRIM).trim()).isNull();
  }
  @Test void staleObservationAndOutagesNeverSupplyAReplacementRange() {
    for (var fixture : List.of(Fixture.STALE, Fixture.UNAVAILABLE, Fixture.TIMED_OUT, Fixture.RATE_LIMITED)) {
      var result = result(fixture);
      assertThat(result.range()).isNull();
      assertThat(result.notice()).isNotBlank();
      assertThat(result.reason()).isEqualTo(switch (fixture) {
        case STALE -> Reason.STALE_OBSERVATION;
        case TIMED_OUT -> Reason.TIMED_OUT;
        case RATE_LIMITED -> Reason.RATE_LIMITED;
        default -> Reason.PROVIDER_UNAVAILABLE;
      });
      assertThat(result.availability()).isEqualTo(fixture == Fixture.TIMED_OUT ? Availability.TIMED_OUT
          : fixture == Fixture.RATE_LIMITED ? Availability.RATE_LIMITED : Availability.UNAVAILABLE);
    }
  }
  @Test void missingRequiredFactsAreUnavailableRatherThanInvented() {
    for (var vehicle : List.of(new TradeVehicle(null, 60000, Condition.GOOD, "EX"),
        new TradeVehicle("1HGCM82633A004352", null, Condition.GOOD, "EX"),
        new TradeVehicle("1HGCM82633A004352", 60000, null, "EX"))) {
      var result = adapter(Fixture.NORMAL).value(vehicle, "27601");
      assertThat(result.availability()).isEqualTo(Availability.UNAVAILABLE);
      assertThat(result.reason()).isEqualTo(Reason.MISSING_FIELDS);
      assertThat(result.range()).isNull();
    }
  }
  @Test void boundaryExposesOnlyDomainTypesAndHasNoFetchOperation() {
    assertThat(TradeValuationAdapter.class.getDeclaredMethods()).extracting(java.lang.reflect.Method::getName)
        .containsExactlyInAnyOrder("provider", "value");
    var method = java.util.Arrays.stream(TradeValuationAdapter.class.getDeclaredMethods())
        .filter(m -> m.getName().equals("value")).findFirst().orElseThrow();
    assertThat(method.getParameterTypes()).containsExactly(TradeVehicle.class, String.class);
    assertThat(method.getReturnType()).isEqualTo(Result.class);
    for (var type : List.of(Result.class, Range.class, Provider.class, TradeVehicle.class))
      for (var field : type.getRecordComponents())
        assertThat(field.getType().getName()).matches("(?:java\\..*|com\\.dealanalyzer\\..*|boolean|long)");
  }
}

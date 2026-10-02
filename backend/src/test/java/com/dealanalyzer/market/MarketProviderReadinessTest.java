package com.dealanalyzer.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.dealanalyzer.buyingcase.EphemeralBuyingCaseService;
import com.dealanalyzer.market.MarketDataProvider.*;
import com.dealanalyzer.market.persistence.MarketAssessmentPersistence;
import com.dealanalyzer.market.persistence.MarketAssessmentPersistence.*;
import com.dealanalyzer.operations.ProviderMeter;
import com.dealanalyzer.operations.ProviderUsageStore;
import com.dealanalyzer.vehicle.VehicleEvidenceService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarketProviderReadinessTest {
  private static final Instant NOW = Instant.now();
  private static final MarketVehicle VEHICLE = new MarketVehicle(
      null, 2021, "Honda", "Accord", "EX-L", 38_000L, "27604");

  @Test void allUnavailablePathsPersistNoPricesAndGiveAReportReason() {
    var good = snapshot("market-provider.v1", NOW);
    List<ProviderResult> responses = List.of(
        ProviderResult.unavailable("Provider temporarily unavailable.", good),
        ProviderResult.available(snapshot("unexpected.v2", NOW), observations()),
        ProviderResult.available(null, observations()),
        ProviderResult.available(snapshot("market-provider.v1", NOW.minus(Duration.ofDays(46))), observations()),
        ProviderResult.available(new ProviderSnapshot("source", "v1", "market-provider.v1", null, "artifact", NOW), observations()));
    List<String> reasons = List.of("temporarily unavailable", "schema drift", "snapshot metadata", "stale", "snapshot metadata");
    for (int index = 0; index < responses.size(); index++) {
      var assessment = create(responses.get(index), false,
          index == 0 ? ProviderMeter.Status.PROVIDER_UNAVAILABLE : ProviderMeter.Status.CONTRACT_FAILURE);
      assertThat(assessment.status()).isEqualTo(MarketAssessment.Status.UNAVAILABLE);
      assertThat(assessment.range()).isNull();
      assertThat(assessment.decisions()).isEmpty();
      assertThat(assessment.limitation()).contains(reasons.get(index));
      var summary = MarketReportEvidence.describe(view(assessment), NOW, false);
      assertThat(String.join(" ", summary.paragraphs())).contains(reasons.get(index), "No market rating is drawn");
    }
    var thrown = create(null, true, ProviderMeter.Status.PROVIDER_FAILURE);
    assertThat(thrown.range()).isNull();
    assertThat(thrown.limitation()).contains("provider failed");
  }

  @Test void thinComparablesGiveNoRangeAndStateNoConclusion() {
    var assessment = create(ProviderResult.available(snapshot("market-provider.v1", NOW),
        observations().subList(0, 2)), false, ProviderMeter.Status.SUCCESS);
    assertThat(assessment.status()).isEqualTo(MarketAssessment.Status.INSUFFICIENT_DATA);
    assertThat(assessment.range()).isNull();
    assertThat(String.join(" ", MarketReportEvidence.describe(view(assessment), NOW, false).paragraphs()))
        .contains("Too few", "No market rating is drawn");
  }

  @Test void cachedAssessmentExpiresAtReportGenerationWithoutRefreshingPrices() {
    var assessment = new MarketAssessmentPolicy().assess(VEHICLE,
        ProviderResult.available(snapshot("market-provider.v1", NOW), observations()));
    assertThat(MarketReportEvidence.current(view(assessment), NOW)).isTrue();
    var expired = NOW.plus(Duration.ofDays(46));
    assertThat(MarketReportEvidence.current(view(assessment), expired)).isFalse();
    var summary = MarketReportEvidence.describe(view(assessment), expired, false);
    assertThat(summary.status()).isEqualTo("UNAVAILABLE");
    assertThat(String.join(" ", summary.paragraphs())).contains("stale", "No market rating is drawn");
    assertThat(assessment.range()).isNotNull(); // Original frozen evidence is retained, never reused as current.
  }

  @Test void availableEvidenceStatesRetrievalWindowAndUncertainty() {
    var assessment = new MarketAssessmentPolicy().assess(VEHICLE,
        ProviderResult.available(snapshot("market-provider.v1", NOW), observations()));
    assertThat(String.join(" ", MarketReportEvidence.describe(view(assessment), NOW, true).paragraphs()))
        .contains("Market data retrieved: " + NOW, "Observation window:", "Uncertainty: low confidence", "uncertainty buffer");
  }

  private MarketAssessment create(ProviderResult response, boolean throwsFailure, ProviderMeter.Status expected) {
    var cases = mock(EphemeralBuyingCaseService.class, RETURNS_DEEP_STUBS);
    when(cases.reopen("case", "token").dealerLocation()).thenReturn("27604");
    var vehicles = mock(VehicleEvidenceService.class);
    when(vehicles.get("case", "token")).thenReturn(new VehicleEvidenceService.VehicleEvidenceView(
        new VehicleEvidenceService.VehicleView(null, 2021, "Honda", "Accord", "EX-L", 38_000L, "MANUAL", NOW),
        null, null, List.of(), "VEHICLE_CONFIRMED", true));
    var provider = mock(MarketDataProvider.class);
    if (throwsFailure) when(provider.retrieve(any())).thenThrow(new IllegalStateException("private payload"));
    else when(provider.retrieve(any())).thenReturn(response);
    var persistence = mock(MarketAssessmentPersistence.class);
    var usage = mock(ProviderUsageStore.class);
    when(usage.start(anyString(), any(), any(), anyLong())).thenReturn("usage");
    try (var ignored = new MeterCloser(new ProviderMeter(usage, Duration.ofSeconds(1), 1))) {
      new MarketAssessmentService(cases, vehicles, provider, persistence, ignored.meter).create("case", "token");
    }
    var assessment = org.mockito.ArgumentCaptor.forClass(MarketAssessment.class);
    verify(persistence).append(eq("case"), any(), assessment.capture(), any());
    verify(usage).start(eq("case"), eq(ProviderMeter.Operation.MARKET_RETRIEVAL), eq(provider), eq(1L));
    verify(usage).finish("usage", expected);
    return assessment.getValue();
  }

  private record MeterCloser(ProviderMeter meter) implements AutoCloseable {
    public void close() { meter.close(); }
  }

  static ProviderSnapshot snapshot(String schema, Instant retrieved) {
    return new ProviderSnapshot("Recorded source", "v1", schema, "display and cache permitted", "fixture://test", retrieved);
  }
  private static List<ComparableObservation> observations() {
    return java.util.stream.IntStream.range(0, 3).mapToObj(index -> new ComparableObservation(
        "id-" + index, "https://example/" + index, 2021, "Honda", "Accord", "EX-L", 38_000L,
        2_500_000L, "USD", "27604", NOW.minus(Duration.ofDays(2 + index)))).toList();
  }
  private static MarketAssessmentView view(MarketAssessment assessment) {
    var p = assessment.provider();
    return new MarketAssessmentView("id", "case", "v1", 1, assessment.status().name(),
        assessment.range() == null ? null : new RangeView(assessment.range().lowMinor(), assessment.range().highMinor(), "USD"),
        "27604", assessment.vintage() == null ? null : new VintageView(assessment.vintage().oldestObservationAt(), assessment.vintage().newestObservationAt()),
        assessment.sampleSize(), assessment.methodVersion(), assessment.confidence().name(), assessment.limitation(),
        new ProviderView("provider", p.source(), p.providerVersion(), p.schemaVersion(), p.licenseMetadata(), p.artifactReference(),
            assessment.status().name(), assessment.limitation(), p.retrievedAt()), List.of(), List.of(), NOW);
  }
}

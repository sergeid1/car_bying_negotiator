package com.dealanalyzer.operations;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.dealanalyzer.insurance.quote.InsuranceQuoteAdapter.*;
import com.dealanalyzer.insurance.quote.*;
import com.dealanalyzer.insurance.quote.InsuranceApplication.*;
import com.dealanalyzer.partner.PartnerOutcome;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class InsuranceQuoteProviderMeterTest {
  private final Provider provider = new Provider("LICENSED_TEST", "v1", "TEST_TERMS", false);
  private final InsuranceQuoteScope caseScope = InsuranceQuoteScope.forCase("case");
  private final InsuranceQuoteScope journeyScope = InsuranceQuoteScope.forJourney("account", "journey");
  private ProviderUsageStore store() {
    var store = mock(ProviderUsageStore.class);
    when(store.startInsuranceQuote(any(), eq(provider))).thenReturn("usage");
    return store;
  }
  private Result available(Instant retrieved, Instant quoted) {
    var coverage = CoverageSet.of(new Coverage(Coverage.Type.COLLISION, null, null, 50000L));
    var quote = new InsuranceQuote(120000, "USD", Term.TWELVE_MONTHS, coverage, quoted, quoted.plus(Duration.ofDays(30)),
        URI.create("https://partner.example/continue"));
    return new Result(Availability.AVAILABLE, null, provider, retrieved, List.of(
        new InsurerEntry("Insurer A", PartnerOutcome.OFFERED, quote),
        new InsurerEntry("Insurer B", PartnerOutcome.DECLINED, null)), new BigDecimal("0.40"));
  }

  @Test void caseAndJourneyCallsRecordResultCostStatusAndLatency() {
    for (var scope : List.of(caseScope, journeyScope))
      for (var availability : Availability.values()) {
        var store = store(); var meter = new ProviderMeter(store, Duration.ofSeconds(1), 1);
        try {
          var reason = availability == Availability.TIMED_OUT ? Reason.TIMED_OUT
              : availability == Availability.RATE_LIMITED ? Reason.RATE_LIMITED : Reason.PROVIDER_UNAVAILABLE;
          var result = availability == Availability.AVAILABLE ? available(Instant.now(), Instant.now())
              : InsuranceQuoteContract.unavailable(provider, reason, Instant.now(), BigDecimal.ZERO);
          assertThat(meter.callInsuranceQuote(scope, provider, () -> result)).isEqualTo(result);
          verify(store).startInsuranceQuote(scope, provider);
          verify(store).insuranceQuoteResult("usage", result);
          verify(store).finish("usage", availability == Availability.AVAILABLE ? ProviderMeter.Status.SUCCESS : ProviderMeter.Status.PROVIDER_UNAVAILABLE);
          verify(store).insuranceQuoteLatency(eq("usage"), longThat(ms -> ms >= 0));
        } finally { meter.close(); }
      }
  }

  @Test void genuineTimeoutIsBoundedAndReturnsNoQuoteOrInventedCost() {
    var store = store(); var meter = new ProviderMeter(store, Duration.ofMillis(50), 1);
    try {
      var result = meter.callInsuranceQuote(caseScope, provider, () -> {
        try { Thread.sleep(5000); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
        return null;
      });
      assertThat(result.reason()).isEqualTo(Reason.TIMED_OUT);
      assertThat(result.insurers()).isEmpty(); assertThat(result.costUsd()).isNull();
      verify(store).finish("usage", ProviderMeter.Status.PROVIDER_UNAVAILABLE);
      verify(store).insuranceQuoteResult("usage", result);
    } finally { meter.close(); }
  }

  @Test void staleFutureOrExpiredResponsesAreRejectedAndReportedCostIsKept() {
    var store = store(); var meter = new ProviderMeter(store, Duration.ofSeconds(1), 1);
    try {
      var old = Instant.now().minus(InsuranceQuoteContract.MAX_AGE).minusSeconds(1);
      var expired = available(Instant.now(), Instant.now().minus(Duration.ofDays(31)));
      for (var response : List.of(available(old, old), available(Instant.now().plusSeconds(120), Instant.now()),
          available(Instant.now(), Instant.now().plusSeconds(120)), expired)) {
        var result = meter.callInsuranceQuote(caseScope, provider, () -> response);
        assertThat(result.availability()).isEqualTo(Availability.UNAVAILABLE);
        assertThat(result.reason()).isIn(Reason.STALE_RESPONSE, Reason.INVALID_RESPONSE);
        assertThat(result.insurers()).isEmpty(); assertThat(result.costUsd()).isEqualByComparingTo("0.40");
      }
      verify(store, times(4)).finish("usage", ProviderMeter.Status.CONTRACT_FAILURE);
    } finally { meter.close(); }
  }

  @Test void wrongProviderDuplicatesNullAndExceptionsStayUnavailableWithoutLeakingProviderText() {
    var store = store(); var meter = new ProviderMeter(store, Duration.ofSeconds(1), 1);
    try {
      var failed = meter.callInsuranceQuote(caseScope, provider, () -> { throw new IllegalStateException("secret partner text"); });
      assertThat(failed.notice()).doesNotContain("secret"); assertThat(failed.insurers()).isEmpty();
      verify(store).finish("usage", ProviderMeter.Status.PROVIDER_FAILURE);
      assertThat(meter.callInsuranceQuote(caseScope, provider, () -> null).reason()).isEqualTo(Reason.INVALID_RESPONSE);
      var good = available(Instant.now(), Instant.now());
      var wrong = new Provider("WRONG", "v1", "WRONG_TERMS", false);
      assertThat(meter.callInsuranceQuote(caseScope, provider, () -> new Result(Availability.AVAILABLE, null, wrong,
          good.retrievedAt(), good.insurers(), null)).reason()).isEqualTo(Reason.INVALID_RESPONSE);
      var duplicate = new Result(Availability.AVAILABLE, null, provider, good.retrievedAt(),
          List.of(good.insurers().getFirst(), new InsurerEntry("INSURER A", PartnerOutcome.DECLINED, null)), null);
      assertThat(meter.callInsuranceQuote(caseScope, provider, () -> duplicate).insurers()).isEmpty();
    } finally { meter.close(); }
  }

  @Test void serviceRefusesToSendOrMeterWithoutConsent() {
    var store = store(); var meter = new ProviderMeter(store, Duration.ofSeconds(1), 1);
    try {
      var adapter = mock(InsuranceQuoteAdapter.class);
      when(adapter.provider()).thenReturn(provider);
      var service = new InsuranceQuoteService(adapter, meter);
      var application = new InsuranceApplication(List.of(new Driver(40, 20, 0, 0)), new Garaging("33602", "FL"),
          new InsuredVehicle(null, 2024, "Toyota", "Camry", null, null),
          CoverageSet.of(new Coverage(Coverage.Type.PROPERTY_DAMAGE_LIABILITY, null, 1000000L, null)), null);
      assertThatThrownBy(() -> service.request(caseScope, application, null)).isInstanceOf(IllegalArgumentException.class);
      verifyNoInteractions(store);
      verify(adapter, never()).request(any(), any());
      var consent = new InsuranceQuoteConsent("partner-consent.v1", Instant.now());
      when(adapter.request(application, consent)).thenReturn(available(Instant.now(), Instant.now()));
      assertThat(service.request(journeyScope, application, consent).availability()).isEqualTo(Availability.AVAILABLE);
      verify(store).startInsuranceQuote(journeyScope, provider);
    } finally { meter.close(); }
  }

  @Test void scopeIsACaseOrAJourneyNeverBoth() {
    assertThatThrownBy(() -> new InsuranceQuoteScope("case", "account", "journey")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new InsuranceQuoteScope(null, null, "journey")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> InsuranceQuoteScope.forCase(" ")).isInstanceOf(IllegalArgumentException.class);
  }
}

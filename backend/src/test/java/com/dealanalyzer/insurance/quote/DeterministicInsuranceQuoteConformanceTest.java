package com.dealanalyzer.insurance.quote;

import static org.assertj.core.api.Assertions.*;
import com.dealanalyzer.insurance.quote.InsuranceApplication.*;
import com.dealanalyzer.partner.PartnerOutcome;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class DeterministicInsuranceQuoteConformanceTest extends InsuranceQuoteAdapterConformance {
  @Override protected Instant now() { return Instant.parse("2026-10-15T12:00:00Z"); }
  @Override protected InsuranceQuoteAdapter adapter(Fixture fixture) {
    return new DeterministicFakeInsuranceQuoteAdapter(Clock.fixed(now(), ZoneOffset.UTC),
        DeterministicFakeInsuranceQuoteAdapter.Scenario.valueOf(fixture.name()));
  }
  private Map<String, InsuranceQuoteAdapter.InsurerEntry> byInsurer(InsuranceApplication application) {
    return adapter(Fixture.NORMAL).request(application, consent()).insurers().stream()
        .collect(Collectors.toMap(InsuranceQuoteAdapter.InsurerEntry::insurer, entry -> entry));
  }
  private InsuranceApplication with(List<Driver> drivers, Integer annualMileage, CoverageSet coverage) {
    var base = application(Fixture.NORMAL);
    var vehicle = base.vehicle();
    return new InsuranceApplication(drivers, base.garaging(), new InsuredVehicle(vehicle.vin(), vehicle.modelYear(),
        vehicle.make(), vehicle.model(), vehicle.trim(), annualMileage), coverage, base.currentPolicy());
  }

  @Test void fixedClockProducesRepeatableLabelledSyntheticQuotes() {
    for (var fixture : Fixture.values()) {
      var adapter = adapter(fixture);
      var first = adapter.request(application(fixture), consent());
      assertThat(adapter.request(application(fixture), consent())).isEqualTo(first);
      assertThat(first.provider().synthetic()).isTrue();
      assertThat(first.provider().name()).contains("SYNTHETIC");
      assertThat(first.costUsd()).isZero();
      if (!first.insurers().isEmpty()) assertThat(first.notice()).contains("Synthetic", "development");
    }
  }

  @Test void outcomesFollowFromTheApplication() {
    var normal = byInsurer(application(Fixture.NORMAL));
    var chosen = chosenCoverage();
    assertThat(normal.get("Synthetic Insurer Alpha").quote().coverage()).isEqualTo(chosen);
    assertThat(normal.get("Synthetic Insurer Alpha").quote().term()).isEqualTo(Term.TWELVE_MONTHS);
    assertThat(normal.get("Synthetic Insurer Beta").quote().coverage()).isEqualTo(chosen);
    assertThat(normal.get("Synthetic Insurer Beta").quote().term()).isEqualTo(Term.SIX_MONTHS);
    assertThat(normal.get("Synthetic Insurer Gamma").quote().coverage()).isNotEqualTo(chosen);
    assertThat(normal.get("Synthetic Insurer Delta").outcome()).isEqualTo(PartnerOutcome.OFFERED);
    assertThat(normal.get("Synthetic Insurer Epsilon").outcome()).isEqualTo(PartnerOutcome.TIMED_OUT);
    assertThat(normal.get("Synthetic Insurer Alpha").quote().continueUrl().toString())
        .startsWith("https://synthetic-insurance-partner.invalid/").doesNotContain("33602", "1HGCM");

    var risky = byInsurer(with(List.of(new Driver(38, 20, 2, 0)), 12000, chosen));
    assertThat(risky.get("Synthetic Insurer Delta").outcome()).isEqualTo(PartnerOutcome.DECLINED);
    assertThat(risky.get("Synthetic Insurer Delta").quote()).isNull();
    assertThat(risky.get("Synthetic Insurer Alpha").quote().premiumMinor())
        .isGreaterThan(normal.get("Synthetic Insurer Alpha").quote().premiumMinor());

    var noMileage = byInsurer(with(List.of(new Driver(38, 20, 0, 0)), null, chosen));
    assertThat(noMileage.get("Synthetic Insurer Epsilon").outcome()).isEqualTo(PartnerOutcome.NEEDS_MORE_INFORMATION);

    var liabilityOnly = CoverageSet.of(new Coverage(Coverage.Type.BODILY_INJURY_LIABILITY, 1000000L, 2000000L, null),
        new Coverage(Coverage.Type.PROPERTY_DAMAGE_LIABILITY, null, 1000000L, null));
    var gamma = byInsurer(with(List.of(new Driver(38, 20, 0, 0)), 12000, liabilityOnly)).get("Synthetic Insurer Gamma");
    assertThat(CoverageComparison.differences(liabilityOnly, gamma.quote().coverage()))
        .containsExactly(new CoverageComparison.Difference(Coverage.Type.PROPERTY_DAMAGE_LIABILITY,
            CoverageComparison.Aspect.PER_ACCIDENT_LIMIT, 1000000L, 500000L));
  }

  @Test void invalidMetadataAmountsAndApplicationsAreRejected() {
    assertThatThrownBy(() -> new InsuranceQuoteAdapter.Provider("", "v1", "terms", false)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new InsuranceQuoteConsent(" ", now())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new InsuranceQuoteConsent("v1", null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Garaging("https://example.com", "FL")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Coverage(Coverage.Type.COLLISION, null, null, -1L)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CoverageSet.of(new Coverage(Coverage.Type.COLLISION, null, null, 1L),
        new Coverage(Coverage.Type.COLLISION, null, null, 2L))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new InsuranceApplication(List.of(), application(Fixture.NORMAL).garaging(),
        application(Fixture.NORMAL).vehicle(), chosenCoverage(), null)).isInstanceOf(IllegalArgumentException.class);
    var quote = adapter(Fixture.NORMAL).request(application(Fixture.NORMAL), consent()).insurers().getFirst().quote();
    assertThatThrownBy(() -> new InsuranceQuoteAdapter.InsuranceQuote(quote.premiumMinor(), "USD", quote.term(),
        quote.coverage(), quote.quotedAt(), quote.expiresAt(), java.net.URI.create("http://insecure.example/q")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new InsuranceQuoteAdapter.InsurerEntry("Declined Insurer", PartnerOutcome.DECLINED, quote))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new Garaging(" 33602 ", " fl ").state()).isEqualTo("FL");
  }
}

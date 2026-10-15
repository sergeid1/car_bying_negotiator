package com.dealanalyzer.insurance.quote;

import static org.assertj.core.api.Assertions.*;
import static com.dealanalyzer.insurance.quote.InsuranceQuoteAdapter.*;
import com.dealanalyzer.insurance.quote.InsuranceApplication.*;
import com.dealanalyzer.partner.PartnerOutcome;
import java.lang.reflect.ParameterizedType;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Every replacement adapter runs these recorded fixture scenarios without paid endpoints. */
public abstract class InsuranceQuoteAdapterConformance {
  public enum Fixture { NORMAL, UNAVAILABLE, TIMED_OUT, RATE_LIMITED }
  protected abstract InsuranceQuoteAdapter adapter(Fixture fixture);
  protected abstract Instant now();

  protected CoverageSet chosenCoverage() {
    return CoverageSet.of(
        new Coverage(Coverage.Type.BODILY_INJURY_LIABILITY, 1000000L, 2000000L, null),
        new Coverage(Coverage.Type.PROPERTY_DAMAGE_LIABILITY, null, 1000000L, null),
        new Coverage(Coverage.Type.PERSONAL_INJURY_PROTECTION, 1000000L, null, 0L),
        new Coverage(Coverage.Type.COLLISION, null, null, 50000L),
        new Coverage(Coverage.Type.COMPREHENSIVE, null, null, 50000L));
  }
  protected InsuranceApplication application(Fixture fixture) {
    return new InsuranceApplication(List.of(new Driver(38, 20, 0, 0)), new Garaging(" 33602 ", "fl"),
        new InsuredVehicle("1HGCM82633A004352", 2023, "Honda", "Accord", "EX", 12000), chosenCoverage(),
        new CurrentPolicy("Current Insurer", chosenCoverage(), 95000, Term.SIX_MONTHS));
  }
  protected InsuranceQuoteConsent consent() {
    return new InsuranceQuoteConsent("synthetic-partner-consent.v1", now().minusSeconds(5));
  }

  private Result result(Fixture fixture) {
    var adapter = adapter(fixture);
    var result = adapter.request(application(fixture), consent());
    assertThat(InsuranceQuoteContract.failure(result, adapter.provider(), now())).isNull();
    assertThat(result.provider().name()).isNotBlank();
    assertThat(result.provider().licenceMetadata()).isNotBlank();
    assertThat(result.retrievedAt()).isNotNull();
    return result;
  }

  @Test void quotesCarryPremiumTermCoverageExpiryLinkAndRetrievalMetadata() {
    var result = result(Fixture.NORMAL);
    assertThat(result.availability()).isEqualTo(Availability.AVAILABLE);
    assertThat(result.insurers()).isNotEmpty();
    assertThat(result.insurers()).anyMatch(entry -> entry.outcome() == PartnerOutcome.OFFERED);
    var names = new HashSet<String>();
    for (var entry : result.insurers()) {
      assertThat(names.add(entry.insurer().toLowerCase())).isTrue();
      assertThat(entry.quote() != null).isEqualTo(entry.outcome().offered());
      if (entry.quote() == null) continue;
      var quote = entry.quote();
      assertThat(quote.premiumMinor()).isPositive();
      assertThat(quote.currency()).isEqualTo("USD");
      assertThat(quote.term()).isIn(Term.SIX_MONTHS, Term.TWELVE_MONTHS);
      assertThat(quote.coverage().coverages()).isNotEmpty();
      assertThat(quote.quotedAt()).isBeforeOrEqualTo(result.retrievedAt().plusSeconds(60));
      assertThat(quote.expiresAt()).isAfter(result.retrievedAt());
      assertThat(quote.continueUrl().getScheme()).isEqualTo("https");
    }
  }

  @Test void outagesNeverSupplyAReplacementQuote() {
    for (var fixture : List.of(Fixture.UNAVAILABLE, Fixture.TIMED_OUT, Fixture.RATE_LIMITED)) {
      var result = result(fixture);
      assertThat(result.insurers()).isEmpty();
      assertThat(result.notice()).isNotBlank();
      assertThat(result.reason()).isEqualTo(switch (fixture) {
        case TIMED_OUT -> Reason.TIMED_OUT;
        case RATE_LIMITED -> Reason.RATE_LIMITED;
        default -> Reason.PROVIDER_UNAVAILABLE;
      });
      assertThat(result.availability()).isEqualTo(fixture == Fixture.TIMED_OUT ? Availability.TIMED_OUT
          : fixture == Fixture.RATE_LIMITED ? Availability.RATE_LIMITED : Availability.UNAVAILABLE);
    }
  }

  @Test void nothingIsSentWithoutTheBuyersConsent() {
    assertThatThrownBy(() -> adapter(Fixture.NORMAL).request(application(Fixture.NORMAL), null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test void boundaryExposesOnlyDomainTypesAndCarriesNoIdentityDetails() {
    assertThat(InsuranceQuoteAdapter.class.getDeclaredMethods()).extracting(java.lang.reflect.Method::getName)
        .containsExactlyInAnyOrder("provider", "request");
    var method = java.util.Arrays.stream(InsuranceQuoteAdapter.class.getDeclaredMethods())
        .filter(m -> m.getName().equals("request")).findFirst().orElseThrow();
    assertThat(method.getParameterTypes()).containsExactly(InsuranceApplication.class, InsuranceQuoteConsent.class);
    assertThat(method.getReturnType()).isEqualTo(Result.class);
    // Walk every record reachable from the application and the result.
    var seen = new HashSet<Class<?>>();
    var pending = new ArrayDeque<Class<?>>(List.of(InsuranceApplication.class, Result.class));
    while (!pending.isEmpty()) {
      var type = pending.pop();
      if (!type.isRecord() || !seen.add(type)) continue;
      for (var component : type.getRecordComponents()) {
        assertThat(component.getType().getName()).matches("(?:java\\..*|com\\.dealanalyzer\\..*|boolean|long|int)");
        assertThat(component.getName().toLowerCase())
            .doesNotContain("ssn", "social", "birth", "dob", "licensenumber", "licencenumber", "driverslicense");
        pending.push(component.getType());
        if (component.getGenericType() instanceof ParameterizedType generic)
          for (var argument : generic.getActualTypeArguments()) if (argument instanceof Class<?> c) pending.push(c);
      }
    }
    assertThat(seen).contains(InsuranceApplication.Driver.class, InsuredVehicle.class, InsuranceQuote.class, Coverage.class);
  }
}

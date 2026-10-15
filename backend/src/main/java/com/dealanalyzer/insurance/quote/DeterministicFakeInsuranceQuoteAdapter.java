package com.dealanalyzer.insurance.quote;

import com.dealanalyzer.insurance.quote.InsuranceApplication.Term;
import com.dealanalyzer.market.DevelopmentEvidenceAdapter;
import com.dealanalyzer.partner.PartnerOutcome;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import static com.dealanalyzer.insurance.quote.InsuranceQuoteAdapter.*;

/**
 * Fixed synthetic insurers, no network. Premiums and outcomes follow from the application alone, and a
 * supplied clock makes every scenario repeatable:
 * <ul>
 *   <li>Alpha quotes the chosen coverage for 12 months; Beta quotes it for 6 months.</li>
 *   <li>Gamma prices different coverage (higher deductibles, or a lower property damage limit).</li>
 *   <li>Delta declines a driver with two or more at-fault accidents or three or more violations.</li>
 *   <li>Epsilon needs more information when annual mileage is missing, and otherwise times out.</li>
 * </ul>
 */
@Component
public class DeterministicFakeInsuranceQuoteAdapter implements InsuranceQuoteAdapter, DevelopmentEvidenceAdapter {
  public enum Scenario { NORMAL, UNAVAILABLE, TIMED_OUT, RATE_LIMITED }
  public static final Duration QUOTE_VALIDITY = Duration.ofDays(30);
  private final Clock clock;
  private final Scenario scenario;
  public DeterministicFakeInsuranceQuoteAdapter() { this(Clock.systemUTC(), Scenario.NORMAL); }
  public DeterministicFakeInsuranceQuoteAdapter(Clock clock, Scenario scenario) { this.clock = clock; this.scenario = scenario; }

  @Override public Provider provider() {
    return new Provider("SYNTHETIC_INSURANCE_PARTNER", "deterministic-insurance.v1", "SYNTHETIC_DISPLAY_AND_CACHE_PERMITTED", true);
  }

  @Override public Result request(InsuranceApplication application, InsuranceQuoteConsent consent) {
    InsuranceQuoteConsent.required(consent);
    if (application == null) throw new IllegalArgumentException("Insurance application required");
    var now = clock.instant();
    Reason reason = switch (scenario) {
      case UNAVAILABLE -> Reason.PROVIDER_UNAVAILABLE;
      case TIMED_OUT -> Reason.TIMED_OUT;
      case RATE_LIMITED -> Reason.RATE_LIMITED;
      case NORMAL -> null;
    };
    if (reason != null) return InsuranceQuoteContract.unavailable(provider(), reason, now, BigDecimal.ZERO);

    long yearly = yearlyPremium(application);
    var chosen = application.coverage();
    var entries = new ArrayList<InsurerEntry>();
    entries.add(offer("Synthetic Insurer Alpha", yearly, Term.TWELVE_MONTHS, chosen, now));
    entries.add(offer("Synthetic Insurer Beta", percent(yearly, 104) / 2, Term.SIX_MONTHS, chosen, now));
    entries.add(offer("Synthetic Insurer Gamma", percent(yearly, 88), Term.TWELVE_MONTHS, different(chosen), now));
    boolean highRisk = application.drivers().stream().anyMatch(driver ->
        driver.atFaultAccidentsLastFiveYears() >= 2 || driver.violationsLastThreeYears() >= 3);
    entries.add(highRisk ? new InsurerEntry("Synthetic Insurer Delta", PartnerOutcome.DECLINED, null)
        : offer("Synthetic Insurer Delta", percent(yearly, 110), Term.TWELVE_MONTHS, chosen, now));
    entries.add(new InsurerEntry("Synthetic Insurer Epsilon", application.vehicle().annualMileage() == null
        ? PartnerOutcome.NEEDS_MORE_INFORMATION : PartnerOutcome.TIMED_OUT, null));
    return new Result(Availability.AVAILABLE, null, provider(), now, entries, BigDecimal.ZERO);
  }

  private static InsurerEntry offer(String insurer, long premiumMinor, Term term, CoverageSet coverage, java.time.Instant now) {
    var slug = insurer.substring(insurer.lastIndexOf(' ') + 1).toLowerCase(java.util.Locale.ROOT);
    var quote = new InsuranceQuote(premiumMinor, "USD", term, coverage, now, now.plus(QUOTE_VALIDITY),
        URI.create("https://synthetic-insurance-partner.invalid/continue/" + slug));
    return new InsurerEntry(insurer, PartnerOutcome.OFFERED, quote);
  }

  /** Whole dollars, so a 6-month premium is always an exact half of a yearly figure. */
  private static long yearlyPremium(InsuranceApplication application) {
    long dollars = 1200;
    for (var driver : application.drivers()) {
      dollars += driver.ageYears() < 25 ? 600 : 0;
      dollars += driver.yearsLicensed() < 3 ? 200 : 0;
      dollars += 250L * driver.atFaultAccidentsLastFiveYears() + 100L * driver.violationsLastThreeYears();
    }
    dollars += 300L * (application.drivers().size() - 1);
    dollars += application.vehicle().modelYear() >= 2022 ? 200 : 0;
    dollars += 20L * (application.garaging().zip().charAt(4) - '0');
    for (var coverage : application.coverage().coverages()) {
      dollars += switch (coverage.type()) {
        case COLLISION -> coverage.deductibleMinor() != null && coverage.deductibleMinor() >= 100000 ? 250 : 400;
        case COMPREHENSIVE -> 120;
        case UNINSURED_MOTORIST -> 90;
        case PERSONAL_INJURY_PROTECTION -> 150;
        case BODILY_INJURY_LIABILITY, PROPERTY_DAMAGE_LIABILITY -> 0;
      };
    }
    return dollars * 200;
  }

  private static long percent(long minor, int percent) { return minor * percent / 100 / 200 * 200; }

  private static CoverageSet different(CoverageSet chosen) {
    var lines = new ArrayList<Coverage>();
    boolean changed = false;
    for (var coverage : chosen.coverages()) {
      if (coverage.deductibleMinor() != null) {
        lines.add(new Coverage(coverage.type(), coverage.perPersonLimitMinor(), coverage.perAccidentLimitMinor(),
            coverage.deductibleMinor() + 50000));
        changed = true;
      } else lines.add(coverage);
    }
    if (!changed) {
      // No deductible to raise: price a $10,000 property damage limit, or $5,000 when $10,000 was chosen.
      boolean tenThousandChosen = chosen.find(Coverage.Type.PROPERTY_DAMAGE_LIABILITY)
          .map(line -> Long.valueOf(1000000L).equals(line.perAccidentLimitMinor())).orElse(false);
      lines.removeIf(coverage -> coverage.type() == Coverage.Type.PROPERTY_DAMAGE_LIABILITY);
      lines.add(new Coverage(Coverage.Type.PROPERTY_DAMAGE_LIABILITY, null, tenThousandChosen ? 500000L : 1000000L, null));
    }
    return new CoverageSet(List.copyOf(lines));
  }
}

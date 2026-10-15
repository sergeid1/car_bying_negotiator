package com.dealanalyzer.insurance.quote;

import static org.assertj.core.api.Assertions.*;
import com.dealanalyzer.insurance.quote.CoverageComparison.Aspect;
import com.dealanalyzer.insurance.quote.CoverageComparison.Difference;
import com.dealanalyzer.insurance.quote.InsuranceApplication.CurrentPolicy;
import com.dealanalyzer.insurance.quote.InsuranceApplication.Term;
import com.dealanalyzer.insurance.quote.InsuranceQuoteAdapter.InsuranceQuote;
import com.dealanalyzer.insurance.quote.InsuranceQuoteAdapter.InsurerEntry;
import com.dealanalyzer.insurance.quote.YearlyDifference.CurrentCoverage;
import com.dealanalyzer.partner.PartnerOutcome;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class CoverageComparisonTest {
  private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
  private final CoverageSet chosen = CoverageSet.of(
      new Coverage(Coverage.Type.BODILY_INJURY_LIABILITY, 1000000L, 2000000L, null),
      new Coverage(Coverage.Type.PROPERTY_DAMAGE_LIABILITY, null, 1000000L, null),
      new Coverage(Coverage.Type.COLLISION, null, null, 50000L));

  private InsurerEntry offer(String insurer, long premiumMinor, Term term, CoverageSet coverage) {
    return new InsurerEntry(insurer, PartnerOutcome.OFFERED, new InsuranceQuote(premiumMinor, "USD", term, coverage,
        NOW, NOW.plusSeconds(86400), URI.create("https://partner.example/continue")));
  }
  private CoverageSet withCollisionDeductible(long deductible) {
    return CoverageSet.of(chosen.coverages().get(0), chosen.coverages().get(1),
        new Coverage(Coverage.Type.COLLISION, null, null, deductible));
  }

  @Test void sameCoverageRequiresEveryLineLimitAndDeductibleToMatch() {
    assertThat(CoverageComparison.differences(chosen, CoverageSet.of(chosen.coverages().reversed().toArray(Coverage[]::new))))
        .isEmpty();
    assertThat(CoverageComparison.differences(chosen, withCollisionDeductible(100000L)))
        .containsExactly(new Difference(Coverage.Type.COLLISION, Aspect.DEDUCTIBLE, 50000L, 100000L));
    var lowerLimits = CoverageSet.of(new Coverage(Coverage.Type.BODILY_INJURY_LIABILITY, 1000000L, 1500000L, null),
        chosen.coverages().get(1),
        new Coverage(Coverage.Type.COMPREHENSIVE, null, null, 50000L));
    assertThat(CoverageComparison.differences(chosen, lowerLimits)).containsExactly(
        new Difference(Coverage.Type.BODILY_INJURY_LIABILITY, Aspect.PER_ACCIDENT_LIMIT, 2000000L, 1500000L),
        new Difference(Coverage.Type.COLLISION, Aspect.NOT_IN_QUOTE, null, null),
        new Difference(Coverage.Type.COMPREHENSIVE, Aspect.NOT_CHOSEN, null, null));
  }

  @Test void differentCoverageIsLabelledAndNeverRankedEvenWhenItsPremiumIsLower() {
    var entries = new ArrayList<>(List.of(
        offer("Insurer B", 150000, Term.TWELVE_MONTHS, chosen),
        offer("Insurer A", 70000, Term.SIX_MONTHS, chosen),
        offer("Insurer C", 90000, Term.TWELVE_MONTHS, withCollisionDeductible(100000L)),
        offer("Insurer D", 150000, Term.TWELVE_MONTHS, chosen),
        new InsurerEntry("Insurer E", PartnerOutcome.DECLINED, null),
        new InsurerEntry("Insurer F", PartnerOutcome.TIMED_OUT, null)));
    var comparison = CoverageComparison.compare(chosen, null, entries);
    assertThat(comparison.sameCoverage()).extracting(compared -> compared.entry().insurer())
        .containsExactly("Insurer A", "Insurer B", "Insurer D");
    assertThat(comparison.sameCoverage()).allMatch(CoverageComparison.Compared::sameCoverage)
        .allMatch(compared -> compared.yearlyDifference() == null);
    assertThat(comparison.differentCoverage()).singleElement().satisfies(compared -> {
      assertThat(compared.entry().insurer()).isEqualTo("Insurer C");
      assertThat(compared.sameCoverage()).isFalse();
      assertThat(compared.differences()).isNotEmpty();
      assertThat(compared.yearlyDifference()).isNull();
    });
    assertThat(comparison.withoutQuote()).extracting(InsurerEntry::insurer).containsExactly("Insurer E", "Insurer F");

    // The partner's order has no effect on the result.
    for (int seed = 0; seed < 5; seed++) {
      Collections.shuffle(entries, new java.util.Random(seed));
      assertThat(CoverageComparison.compare(chosen, null, entries)).isEqualTo(comparison);
    }
  }

  @Test void yearlyDifferenceIsComputedAtTheChosenCoverageAgainstTheCurrentPremium() {
    var current = new CurrentPolicy("Current Insurer", chosen, 80000, Term.SIX_MONTHS);
    var comparison = CoverageComparison.compare(chosen, current, List.of(
        offer("Insurer A", 140000, Term.TWELVE_MONTHS, chosen),
        offer("Insurer B", 85000, Term.SIX_MONTHS, chosen),
        offer("Insurer C", 100000, Term.TWELVE_MONTHS, withCollisionDeductible(100000L))));
    assertThat(comparison.sameCoverage()).extracting(CoverageComparison.Compared::yearlyDifference).containsExactly(
        new YearlyDifference(140000, 160000, -20000, CurrentCoverage.SAME_AS_CHOSEN),
        new YearlyDifference(170000, 160000, 10000, CurrentCoverage.SAME_AS_CHOSEN));
    assertThat(comparison.differentCoverage().getFirst().yearlyDifference()).isNull();

    var quote = offer("Insurer A", 140000, Term.TWELVE_MONTHS, chosen).quote();
    assertThat(YearlyDifference.of(quote, new CurrentPolicy(null, null, 150000, Term.TWELVE_MONTHS), chosen).currentCoverage())
        .isEqualTo(CurrentCoverage.NOT_ENTERED);
    assertThat(YearlyDifference.of(quote, new CurrentPolicy(null, withCollisionDeductible(25000L), 150000, Term.TWELVE_MONTHS), chosen)
        .currentCoverage()).isEqualTo(CurrentCoverage.DIFFERENT_FROM_CHOSEN);
    assertThatThrownBy(() -> new YearlyDifference(100, 50, 40, CurrentCoverage.NOT_ENTERED))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test void insuranceQuoteCodeNeverWordsADifferenceAsSavings() throws Exception {
    var forbidden = Pattern.compile("(?i)sav(e|es|ed|ing|ings)\\b|cheaper|guarantee");
    var root = Path.of("src/main/java/com/dealanalyzer/insurance");
    try (var files = Files.walk(root)) {
      var sources = files.filter(file -> file.toString().endsWith(".java")).toList();
      assertThat(sources).isNotEmpty();
      for (var file : sources)
        assertThat(forbidden.matcher(Files.readString(file)).find()).as(file.toString()).isFalse();
    }
  }
}

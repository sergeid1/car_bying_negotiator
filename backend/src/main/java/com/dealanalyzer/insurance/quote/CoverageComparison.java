package com.dealanalyzer.insurance.quote;

import com.dealanalyzer.insurance.quote.InsuranceApplication.CurrentPolicy;
import com.dealanalyzer.insurance.quote.InsuranceQuoteAdapter.InsurerEntry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Compares quotes only at the coverage the Buyer chose (INS-03). A quote is "same coverage" only when every
 * line, limit and deductible matches; any other quote is listed with its differences and never ordered
 * against the same-coverage quotes. Order depends on premium and insurer name only, never on the partner's
 * order or anything a provider pays.
 */
public final class CoverageComparison {
  private CoverageComparison() {}

  public enum Aspect { NOT_IN_QUOTE, NOT_CHOSEN, PER_PERSON_LIMIT, PER_ACCIDENT_LIMIT, DEDUCTIBLE }

  /** One way the priced coverage departs from the Buyer's choice; amounts are null for a missing line. */
  public record Difference(Coverage.Type type, Aspect aspect, Long chosenMinor, Long pricedMinor) {}

  public record Compared(InsurerEntry entry, List<Difference> differences, YearlyDifference yearlyDifference) {
    public Compared { differences = List.copyOf(differences); }
    public boolean sameCoverage() { return differences.isEmpty(); }
  }

  /**
   * {@code sameCoverage} is ordered by yearly premium; {@code differentCoverage} and {@code withoutQuote}
   * are listed by insurer name and carry no rank.
   */
  public record Comparison(List<Compared> sameCoverage, List<Compared> differentCoverage, List<InsurerEntry> withoutQuote) {
    public Comparison {
      sameCoverage = List.copyOf(sameCoverage); differentCoverage = List.copyOf(differentCoverage);
      withoutQuote = List.copyOf(withoutQuote);
    }
  }

  public static List<Difference> differences(CoverageSet chosen, CoverageSet priced) {
    Objects.requireNonNull(chosen); Objects.requireNonNull(priced);
    var differences = new ArrayList<Difference>();
    for (var type : EnumSet.allOf(Coverage.Type.class)) {
      var wanted = chosen.find(type).orElse(null);
      var got = priced.find(type).orElse(null);
      if (wanted == null && got == null) continue;
      if (got == null) { differences.add(new Difference(type, Aspect.NOT_IN_QUOTE, null, null)); continue; }
      if (wanted == null) { differences.add(new Difference(type, Aspect.NOT_CHOSEN, null, null)); continue; }
      compare(differences, type, Aspect.PER_PERSON_LIMIT, wanted.perPersonLimitMinor(), got.perPersonLimitMinor());
      compare(differences, type, Aspect.PER_ACCIDENT_LIMIT, wanted.perAccidentLimitMinor(), got.perAccidentLimitMinor());
      compare(differences, type, Aspect.DEDUCTIBLE, wanted.deductibleMinor(), got.deductibleMinor());
    }
    return List.copyOf(differences);
  }

  public static Comparison compare(CoverageSet chosen, CurrentPolicy current, List<InsurerEntry> entries) {
    var same = new ArrayList<Compared>();
    var different = new ArrayList<Compared>();
    var without = new ArrayList<InsurerEntry>();
    for (var entry : entries) {
      if (entry.quote() == null) { without.add(entry); continue; }
      var differences = differences(chosen, entry.quote().coverage());
      if (differences.isEmpty()) same.add(new Compared(entry, differences, YearlyDifference.of(entry.quote(), current, chosen)));
      else different.add(new Compared(entry, differences, null));
    }
    Comparator<InsurerEntry> byName = Comparator.comparing((InsurerEntry entry) -> entry.insurer().toLowerCase(Locale.ROOT))
        .thenComparing(InsurerEntry::insurer);
    same.sort(Comparator.comparingLong((Compared compared) -> compared.entry().quote().yearlyPremiumMinor())
        .thenComparing(Compared::entry, byName)
        .thenComparingInt(compared -> compared.entry().quote().term().months()));
    different.sort(Comparator.comparing(Compared::entry, byName));
    without.sort(byName);
    return new Comparison(same, different, without);
  }

  private static void compare(List<Difference> differences, Coverage.Type type, Aspect aspect, Long chosen, Long priced) {
    if (!Objects.equals(chosen, priced)) differences.add(new Difference(type, aspect, chosen, priced));
  }
}

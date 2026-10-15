package com.dealanalyzer.insurance.quote;

import com.dealanalyzer.insurance.quote.InsuranceApplication.CurrentPolicy;
import com.dealanalyzer.insurance.quote.InsuranceQuoteAdapter.InsuranceQuote;

/**
 * A quote's yearly premium beside the Buyer's current yearly premium (INS-04). The difference is the quote
 * minus the current premium: negative means the quote costs less per year, positive means it costs more.
 * It states arithmetic only and promises nothing about what the Buyer will pay.
 */
public record YearlyDifference(long quoteYearlyMinor, long currentYearlyMinor, long quoteMinusCurrentMinor,
    CurrentCoverage currentCoverage) {
  /** Whether the Buyer's current policy was entered with the same coverage they chose for these quotes. */
  public enum CurrentCoverage { SAME_AS_CHOSEN, DIFFERENT_FROM_CHOSEN, NOT_ENTERED }

  public YearlyDifference {
    if (quoteYearlyMinor <= 0 || currentYearlyMinor <= 0 || currentCoverage == null
        || quoteMinusCurrentMinor != Math.subtractExact(quoteYearlyMinor, currentYearlyMinor))
      throw new IllegalArgumentException("Invalid yearly difference");
  }

  /** Null when the Buyer gave no current premium. */
  public static YearlyDifference of(InsuranceQuote quote, CurrentPolicy current, CoverageSet chosen) {
    if (current == null) return null;
    var coverage = current.coverage() == null ? CurrentCoverage.NOT_ENTERED
        : current.coverage().equals(chosen) ? CurrentCoverage.SAME_AS_CHOSEN : CurrentCoverage.DIFFERENT_FROM_CHOSEN;
    long quoteYearly = quote.yearlyPremiumMinor();
    long currentYearly = current.yearlyPremiumMinor();
    return new YearlyDifference(quoteYearly, currentYearly, Math.subtractExact(quoteYearly, currentYearly), coverage);
  }
}

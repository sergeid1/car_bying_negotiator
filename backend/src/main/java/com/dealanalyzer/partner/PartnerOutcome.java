package com.dealanalyzer.partner;

/**
 * What one insurer or lender behind a licensed partner returned for the Buyer's application.
 * Shared by the insurance quote and loan pre-approval adapters so both screens state outcomes alike.
 * Only {@link #OFFERED} carries a quote or pre-approval; every other outcome carries none.
 */
public enum PartnerOutcome {
  OFFERED, DECLINED, NEEDS_MORE_INFORMATION, UNAVAILABLE, TIMED_OUT;

  public boolean offered() { return this == OFFERED; }
}

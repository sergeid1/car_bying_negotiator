package com.dealanalyzer.finance;

import java.math.BigDecimal;
import java.util.List;

/** Detached, buyer-confirmed inputs; money is USD cents and APR is a percentage. */
public record FinanceLoan(
    String currency, Long purchasePriceMinor, Long outTheDoorTotalMinor, Long netAmountDueMinor,
    Long principalMinor, BigDecimal aprPercent, Integer termMonths, String schedule,
    String paymentTiming, Long financedFeesMinor, Long upfrontFeesMinor,
    Long cashPaidAtClosingMinor, Long deferredChargesMinor, Long quotedPaymentMinor,
    List<AddOn> financedAddOns) {
  public FinanceLoan { financedAddOns = financedAddOns == null ? null : List.copyOf(financedAddOns); }
  public record AddOn(String label, Long cashPriceMinor) {}
}

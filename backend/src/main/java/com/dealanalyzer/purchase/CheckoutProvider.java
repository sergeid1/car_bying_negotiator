package com.dealanalyzer.purchase;

/** Replaceable checkout seam. No payment credentials enter the service. */
public interface CheckoutProvider {
  String name();
  Result checkout(String purchaseId, String offerId, long amountMinor, String currency);
  record Result(String status, String checkoutUrl) {}
}

package com.dealanalyzer.purchase;

import org.springframework.stereotype.Component;

@Component
public final class DevelopmentNoChargeCheckout implements CheckoutProvider {
  public String name() { return "DEVELOPMENT_NO_CHARGE"; }
  public Result checkout(String purchaseId, String offerId, long amountMinor, String currency) {
    return new Result("PAID", null);
  }
}

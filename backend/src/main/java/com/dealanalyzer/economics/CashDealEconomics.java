package com.dealanalyzer.economics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.List;
import java.util.Set;

/** Pure domain types for a reproducible cash purchase calculation. */
public final class CashDealEconomics {
  private CashDealEconomics() {}

  public record Money(BigDecimal amount, Currency currency) {
    public Money {
      if (amount == null || currency == null) throw new IllegalArgumentException("Money is required");
    }
  }

  public record Amount(long minorUnits, String currency) {}

  public enum LineType {
    SELLING_PRICE,
    DEALER_DISCOUNT,
    REBATE,
    DEALER_FEE,
    ADD_ON,
    GOVERNMENT_CHARGE,
    TRADE_ALLOWANCE,
    TRADE_PAYOFF,
    DOWN_PAYMENT,
    OUTSIDE_FINANCING,
    OTHER_BUYER_FUNDS,
    CREDIT,
    QUOTED_TAX,
    UNKNOWN
  }

  public enum TaxTreatment { TAXABLE, NOT_TAXABLE, TAX_CREDIT, UNKNOWN }

  public enum Eligibility { VERIFIED, UNVERIFIED, NOT_APPLICABLE }

  public record LineItem(
      String evidence,
      String label,
      Money amount,
      LineType type,
      TaxTreatment taxTreatment,
      Eligibility eligibility) {}

  public record Offer(
      String id,
      int version,
      Currency currency,
      List<LineItem> lineItems,
      Money quotedOutTheDoorTotal,
      String quotedTotalEvidence) {
    public Offer {
      lineItems = List.copyOf(lineItems);
    }
  }

  public record Policy(
      String version,
      String jurisdiction,
      Currency currency,
      BigDecimal taxRate,
      Set<LineType> taxableLineTypes,
      boolean tradeTaxCreditEnabled,
      int currencyScale,
      RoundingMode roundingMode,
      BigDecimal reconciliationTolerance) {
    public Policy {
      taxableLineTypes = Set.copyOf(taxableLineTypes);
    }
  }

  public record TradeTerms(Amount allowance, Amount payoff, Amount netCredit) {}

  public record PriceReductions(
      Amount dealerDiscounts,
      Amount verifiedRebates,
      Amount conditionalRebatesExcluded,
      Amount guaranteed) {}

  public record TaxCalculation(
      BigDecimal taxableCharges,
      BigDecimal tradeCreditAvailable,
      BigDecimal tradeCreditApplied,
      BigDecimal taxableBasis,
      BigDecimal rate,
      BigDecimal unrounded) {}

  public record RoundingDisclosure(
      int currencyScale, RoundingMode mode, String appliedAt) {}

  public record Reconciliation(
      Amount quotedTotal,
      Amount recomputedTotal,
      Amount discrepancy,
      Amount tolerance,
      boolean withinTolerance,
      String evidence,
      String reason) {}

  public record ClassifiedLineItem(
      String evidence,
      String label,
      LineType type,
      Amount amount,
      TaxTreatment taxTreatment,
      Eligibility eligibility,
      String treatmentReason) {}

  public record Result(
      String dealerOfferId,
      int dealerOfferVersion,
      String policyVersion,
      String jurisdiction,
      Amount vehicleSubtotal,
      Amount dealerCharges,
      Amount taxes,
      Amount governmentCharges,
      Amount unknownCharges,
      Amount outTheDoorTotal,
      TradeTerms tradeTerms,
      Amount downPayment,
      Amount credits,
      Amount netAmountDue,
      PriceReductions priceReductions,
      TaxCalculation taxCalculation,
      RoundingDisclosure rounding,
      Reconciliation reconciliation,
      List<ClassifiedLineItem> lineItems) {}
}

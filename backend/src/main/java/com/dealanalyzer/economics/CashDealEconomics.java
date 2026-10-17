package com.dealanalyzer.economics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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
      BigDecimal reconciliationTolerance,
      List<TaxComponent> taxComponents,
      TaxRules taxRules) {
    public Policy {
      taxableLineTypes = Set.copyOf(taxableLineTypes);
      taxComponents = taxComponents == null ? List.of() : List.copyOf(taxComponents);
      taxRules = taxRules == null ? TaxRules.NONE : taxRules;
    }

    public Policy(String version, String jurisdiction, Currency currency, BigDecimal taxRate,
        Set<LineType> taxableLineTypes, boolean tradeTaxCreditEnabled, int currencyScale,
        RoundingMode roundingMode, BigDecimal reconciliationTolerance, List<TaxComponent> taxComponents) {
      this(version, jurisdiction, currency, taxRate, taxableLineTypes, tradeTaxCreditEnabled,
          currencyScale, roundingMode, reconciliationTolerance, taxComponents, TaxRules.NONE);
    }

    public Policy(String version, String jurisdiction, Currency currency, BigDecimal taxRate,
        Set<LineType> taxableLineTypes, boolean tradeTaxCreditEnabled, int currencyScale,
        RoundingMode roundingMode, BigDecimal reconciliationTolerance) {
      this(version, jurisdiction, currency, taxRate, taxableLineTypes, tradeTaxCreditEnabled,
          currencyScale, roundingMode, reconciliationTolerance, List.of());
    }
  }

  public enum TaxBase { TAXABLE_PRICE_AFTER_TRADE }

  /**
   * Pack data beyond the components (ticket 137). {@code taxName} is what the state calls the tax.
   * {@code nonTaxableCharges} lists, per taxable line type, label terms for separately stated charges the
   * state does not tax, such as a Texas documentary fee or a service contract, so the line type alone does
   * not decide. {@code paidAtTitling} marks a tax collected when the car is titled rather than always on the
   * dealer's invoice, and {@code buyerNote} says so to the Buyer.
   */
  public record TaxRules(String taxName, Map<LineType, List<String>> nonTaxableCharges, boolean paidAtTitling,
      String buyerNote) {
    public static final TaxRules NONE = new TaxRules(null, Map.of(), false, null);

    public TaxRules {
      var copy = new java.util.EnumMap<LineType, List<String>>(LineType.class);
      if (nonTaxableCharges != null) for (var entry : nonTaxableCharges.entrySet()) {
        if (entry.getKey() == null || entry.getKey() == LineType.SELLING_PRICE || entry.getValue() == null
            || entry.getValue().isEmpty() || entry.getValue().stream().anyMatch(term -> term == null || term.isBlank()))
          throw new IllegalArgumentException("Invalid non-taxable charge terms");
        copy.put(entry.getKey(), List.copyOf(entry.getValue()));
      }
      nonTaxableCharges = java.util.Collections.unmodifiableMap(copy);
    }

    /** The term that makes a separately stated line not taxable under the pack, or null. */
    public String nonTaxableTerm(LineType type, String label) {
      if (label == null) return null;
      String text = label.toLowerCase(Locale.ROOT);
      for (String term : nonTaxableCharges.getOrDefault(type, List.of())) {
        if (Pattern.compile("\\b" + Pattern.quote(term.toLowerCase(Locale.ROOT))).matcher(text).find()) return term;
      }
      return null;
    }
  }

  /** A sourced component; baseCap limits the base after the trade deduction, never the allowance. */
  public record TaxComponent(String name, BigDecimal rate, TaxBase base, BigDecimal baseCap,
      String citation, String checkedOn) {
    public TaxComponent {
      if (name == null || rate == null || rate.signum() < 0 || base == null
          || baseCap != null && baseCap.signum() < 0)
        throw new IllegalArgumentException("Invalid tax component");
    }

    public BigDecimal tax(BigDecimal taxableBasis) {
      BigDecimal basis = taxableBasis.max(BigDecimal.ZERO);
      return (baseCap == null ? basis : basis.min(baseCap)).multiply(rate);
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
      BigDecimal unrounded,
      List<TaxComponent> components,
      String taxName,
      String buyerNote) {
    public TaxCalculation {
      components = components == null ? List.of() : List.copyOf(components);
    }
    public TaxCalculation(BigDecimal taxableCharges, BigDecimal tradeCreditAvailable,
        BigDecimal tradeCreditApplied, BigDecimal taxableBasis, BigDecimal rate, BigDecimal unrounded,
        List<TaxComponent> components) {
      this(taxableCharges, tradeCreditAvailable, tradeCreditApplied, taxableBasis, rate, unrounded, components,
          null, null);
    }
    public TaxCalculation(BigDecimal taxableCharges, BigDecimal tradeCreditAvailable,
        BigDecimal tradeCreditApplied, BigDecimal taxableBasis, BigDecimal rate, BigDecimal unrounded) {
      this(taxableCharges, tradeCreditAvailable, tradeCreditApplied, taxableBasis, rate, unrounded, List.of());
    }

    public BigDecimal taxAt(BigDecimal basis) {
      return components.isEmpty() ? basis.max(BigDecimal.ZERO).multiply(rate)
          : components.stream().map(component -> component.tax(basis))
              .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
  }

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

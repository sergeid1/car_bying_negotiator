package com.dealanalyzer.economics;

import com.dealanalyzer.economics.CashDealEconomics.Amount;
import com.dealanalyzer.economics.CashDealEconomics.ClassifiedLineItem;
import com.dealanalyzer.economics.CashDealEconomics.Eligibility;
import com.dealanalyzer.economics.CashDealEconomics.LineItem;
import com.dealanalyzer.economics.CashDealEconomics.LineType;
import com.dealanalyzer.economics.CashDealEconomics.Money;
import com.dealanalyzer.economics.CashDealEconomics.Offer;
import com.dealanalyzer.economics.CashDealEconomics.Policy;
import com.dealanalyzer.economics.CashDealEconomics.PriceReductions;
import com.dealanalyzer.economics.CashDealEconomics.Reconciliation;
import com.dealanalyzer.economics.CashDealEconomics.Result;
import com.dealanalyzer.economics.CashDealEconomics.RoundingDisclosure;
import com.dealanalyzer.economics.CashDealEconomics.TaxCalculation;
import com.dealanalyzer.economics.CashDealEconomics.TaxTreatment;
import com.dealanalyzer.economics.CashDealEconomics.TradeTerms;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

/** Currency-aware arithmetic with no persistence or framework dependencies. */
public final class CashDealEconomicsCalculator {
  private static final BigDecimal ZERO = BigDecimal.ZERO;

  public Result calculate(Offer offer, Policy policy) {
    requireCompatible(offer, policy);
    BigDecimal sellingPrice = sum(offer, LineType.SELLING_PRICE, null);
    BigDecimal discounts = sum(offer, LineType.DEALER_DISCOUNT, null);
    BigDecimal verifiedRebates = sum(offer, LineType.REBATE, Eligibility.VERIFIED);
    BigDecimal excludedRebates = sum(offer, LineType.REBATE, Eligibility.UNVERIFIED);
    BigDecimal vehicleSubtotal = sellingPrice.subtract(discounts).subtract(verifiedRebates);
    BigDecimal dealerFees = sum(offer, LineType.DEALER_FEE, null);
    BigDecimal addOns = sum(offer, LineType.ADD_ON, null);
    BigDecimal dealerCharges = dealerFees.add(addOns);
    BigDecimal governmentCharges = sum(offer, LineType.GOVERNMENT_CHARGE, null);
    BigDecimal unknownCharges = sum(offer, LineType.UNKNOWN, null);
    BigDecimal tradeAllowance = sum(offer, LineType.TRADE_ALLOWANCE, null);
    BigDecimal tradePayoff = sum(offer, LineType.TRADE_PAYOFF, null);
    BigDecimal downPayment = sum(offer, LineType.DOWN_PAYMENT, null);
    BigDecimal credits = sum(offer, LineType.OUTSIDE_FINANCING, null)
        .add(sum(offer, LineType.OTHER_BUYER_FUNDS, null))
        .add(sum(offer, LineType.CREDIT, null));

    BigDecimal taxableCharges = taxableCharges(offer, vehicleSubtotal, policy);
    BigDecimal availableTaxCredit = policy.tradeTaxCreditEnabled()
        ? (policy.taxComponents().isEmpty() ? taxCredits(offer) : tradeAllowance) : ZERO;
    BigDecimal appliedTaxCredit = availableTaxCredit.min(taxableCharges.max(ZERO));
    BigDecimal taxableBasis = taxableCharges.subtract(appliedTaxCredit).max(ZERO);
    BigDecimal unroundedTax = taxAt(taxableBasis, policy);
    BigDecimal taxes = unroundedTax.setScale(policy.currencyScale(), policy.roundingMode());
    BigDecimal otd = vehicleSubtotal.add(dealerCharges).add(governmentCharges)
        .add(unknownCharges).add(taxes);
    BigDecimal netTradeCredit = tradeAllowance.subtract(tradePayoff);
    BigDecimal netDue = otd.subtract(netTradeCredit).subtract(downPayment).subtract(credits);

    Amount recomputed = amount(otd, policy);
    List<ClassifiedLineItem> classified = new ArrayList<>();
    for (LineItem item : offer.lineItems()) {
      classified.add(new ClassifiedLineItem(
          item.evidence(), item.label(), item.type(), amount(item.amount().amount(), policy),
          item.taxTreatment(), item.eligibility(), treatmentReason(item, policy)));
    }

    return new Result(
        offer.id(), offer.version(), policy.version(), policy.jurisdiction(),
        amount(vehicleSubtotal, policy), amount(dealerCharges, policy), amount(taxes, policy),
        amount(governmentCharges, policy), amount(unknownCharges, policy), recomputed,
        new TradeTerms(amount(tradeAllowance, policy), amount(tradePayoff, policy),
            amount(netTradeCredit, policy)),
        amount(downPayment, policy), amount(credits, policy), amount(netDue, policy),
        new PriceReductions(amount(discounts, policy), amount(verifiedRebates, policy),
            amount(excludedRebates, policy), amount(discounts.add(verifiedRebates), policy)),
        new TaxCalculation(taxableCharges, availableTaxCredit, appliedTaxCredit, taxableBasis,
            policy.taxRate(), unroundedTax, policy.taxComponents(), policy.taxRules().taxName(),
            policy.taxRules().buyerNote()),
        new RoundingDisclosure(policy.currencyScale(), policy.roundingMode(),
            "TAX_AND_FINAL_MONETARY_OUTPUTS"),
        reconcile(offer, policy, recomputed, taxes), List.copyOf(classified));
  }

  /** Shared by economics and the outside trade-offer comparison. */
  public static BigDecimal taxAt(BigDecimal basis, Policy policy) {
    return policy.taxComponents().isEmpty() ? basis.max(ZERO).multiply(policy.taxRate())
        : policy.taxComponents().stream().map(component -> component.tax(basis))
            .reduce(ZERO, BigDecimal::add);
  }

  private static BigDecimal taxableCharges(Offer offer, BigDecimal vehicleSubtotal, Policy policy) {
    BigDecimal total = ZERO;
    if (policy.taxableLineTypes().contains(LineType.SELLING_PRICE)) total = total.add(vehicleSubtotal);
    for (LineItem item : offer.lineItems()) {
      if (policy.taxableLineTypes().contains(item.type())
          && item.type() != LineType.SELLING_PRICE
          && item.type() != LineType.DEALER_DISCOUNT
          && item.type() != LineType.REBATE
          && item.type() != LineType.QUOTED_TAX
          && policy.taxRules().nonTaxableTerm(item.type(), item.label()) == null) {
        total = total.add(item.amount().amount());
      }
    }
    return total;
  }

  private static BigDecimal taxCredits(Offer offer) {
    return offer.lineItems().stream()
        .filter(item -> item.taxTreatment() == TaxTreatment.TAX_CREDIT)
        .map(item -> item.amount().amount()).reduce(ZERO, BigDecimal::add);
  }

  private static BigDecimal sum(Offer offer, LineType type, Eligibility eligibility) {
    return offer.lineItems().stream().filter(item -> item.type() == type)
        .filter(item -> eligibility == null || item.eligibility() == eligibility)
        .map(item -> item.amount().amount()).reduce(ZERO, BigDecimal::add);
  }

  private static Reconciliation reconcile(Offer offer, Policy policy, Amount recomputed, BigDecimal taxes) {
    if (offer.quotedOutTheDoorTotal() == null) {
      Amount unavailable = new Amount(0, policy.currency().getCurrencyCode());
      return new Reconciliation(null, recomputed, unavailable,
          amount(policy.reconciliationTolerance(), policy), false, offer.quotedTotalEvidence(),
          "Dealer Offer does not contain a quoted OTD total");
    }
    Amount quoted = amount(offer.quotedOutTheDoorTotal().amount(), policy);
    BigDecimal discrepancyValue = offer.quotedOutTheDoorTotal().amount()
        .subtract(toMajor(recomputed, policy));
    Amount discrepancy = amount(discrepancyValue, policy);
    boolean within = discrepancyValue.abs().compareTo(policy.reconciliationTolerance()) <= 0;
    String reason = within
        ? "Quoted total is within the policy reconciliation tolerance"
        : "Quoted total differs from the policy recomputation; no favorable classification inferred";
    // A tax paid when the car is titled may be left off the dealer's total; it is still owed and still counted.
    if (!within && policy.taxRules().paidAtTitling() && taxes.signum() > 0
        && discrepancyValue.add(taxes).abs().compareTo(policy.reconciliationTolerance()) <= 0) {
      within = true;
      reason = "Quoted total leaves out the " + policy.taxRules().taxName() + ", which is paid when the car is "
          + "titled; the recomputed total includes it";
    }
    return new Reconciliation(quoted, recomputed, discrepancy,
        amount(policy.reconciliationTolerance(), policy), within,
        offer.quotedTotalEvidence(), reason);
  }

  private static BigDecimal toMajor(Amount amount, Policy policy) {
    return BigDecimal.valueOf(amount.minorUnits(), policy.currencyScale());
  }

  private static Amount amount(BigDecimal value, Policy policy) {
    BigDecimal rounded = value.setScale(policy.currencyScale(), policy.roundingMode());
    long minor = rounded.movePointRight(policy.currencyScale()).longValueExact();
    return new Amount(minor, policy.currency().getCurrencyCode());
  }

  private static String treatmentReason(LineItem item, Policy policy) {
    String term = policy.taxRules().nonTaxableTerm(item.type(), item.label());
    if (term != null && policy.taxableLineTypes().contains(item.type()) && item.type() != LineType.SELLING_PRICE) {
      return "Not taxable under " + policy.version() + ": a separately stated charge matching \"" + term + "\"";
    }
    return treatmentReason(item);
  }

  private static String treatmentReason(LineItem item) {
    if (item.type() == LineType.UNKNOWN) {
      return "Unknown charge retained in OTD and classified neutrally";
    }
    if (item.type() == LineType.REBATE && item.eligibility() != Eligibility.VERIFIED) {
      return "Conditional rebate excluded until Buyer eligibility is verified";
    }
    if (item.type() == LineType.TRADE_ALLOWANCE || item.type() == LineType.TRADE_PAYOFF
        || item.type() == LineType.DOWN_PAYMENT || item.type() == LineType.OUTSIDE_FINANCING
        || item.type() == LineType.OTHER_BUYER_FUNDS) {
      return "Buyer-provided value applied after OTD and excluded from dealer savings";
    }
    if (item.type() == LineType.CREDIT) {
      return "Credit applied after OTD and excluded from dealer savings";
    }
    return "Applied according to jurisdiction policy";
  }

  private static void requireCompatible(Offer offer, Policy policy) {
    if (!offer.currency().equals(policy.currency())) {
      throw new IllegalArgumentException("Dealer Offer currency does not match jurisdiction policy");
    }
    for (LineItem item : offer.lineItems()) requireCurrency(item.amount(), offer.currency());
    if (offer.quotedOutTheDoorTotal() != null) {
      requireCurrency(offer.quotedOutTheDoorTotal(), offer.currency());
    }
    if (policy.currencyScale() != policy.currency().getDefaultFractionDigits()) {
      throw new IllegalArgumentException("Policy currency scale does not match its currency");
    }
  }

  private static void requireCurrency(Money money, Currency expected) {
    if (!money.currency().equals(expected)) {
      throw new IllegalArgumentException("Mixed currencies are not supported");
    }
  }
}

package com.dealanalyzer.report;

import com.dealanalyzer.buyingcase.SyntheticAnalysisRequest.DealerOfferInput;
import com.dealanalyzer.report.DealAnalysis.Currency;
import com.dealanalyzer.report.DealAnalysis.Economics;
import com.dealanalyzer.report.DealAnalysis.MoneyAmount;
import com.dealanalyzer.report.DealAnalysis.Precision;
import org.springframework.stereotype.Component;

@Component
final class CashDealCalculator {
  Economics calculate(DealerOfferInput offer) {
    long total = Math.addExact(
        Math.addExact(offer.sellingPriceMinor(), offer.dealerFeesMinor()),
        Math.addExact(offer.addOnsMinor(),
            Math.addExact(offer.taxesMinor(), offer.governmentChargesMinor())));
    Currency currency = Currency.valueOf(offer.currency().name());
    return new Economics(
        exact(offer.sellingPriceMinor(), currency, "Synthetic Dealer Offer: selling price"),
        exact(offer.dealerFeesMinor(), currency, "Synthetic Dealer Offer: dealer fees"),
        exact(offer.addOnsMinor(), currency, "Synthetic Dealer Offer: add-ons"),
        exact(offer.taxesMinor(), currency, "Synthetic Dealer Offer: taxes"),
        exact(offer.governmentChargesMinor(), currency, "Synthetic Dealer Offer: government charges"),
        exact(total, currency, "cash-otd-policy.v1 calculation"),
        "cash-otd-policy.v1");
  }

  private static MoneyAmount exact(long minorUnits, Currency currency, String source) {
    return new MoneyAmount(minorUnits, currency, Precision.EXACT, source);
  }
}

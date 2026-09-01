package com.dealanalyzer.report;

import com.dealanalyzer.buyingcase.SyntheticAnalysisRequest.DealerOfferInput;
import com.dealanalyzer.report.DealAnalysis.Currency;
import com.dealanalyzer.report.DealAnalysis.CalculationStep;
import com.dealanalyzer.report.DealAnalysis.Economics;
import com.dealanalyzer.report.DealAnalysis.MoneyAmount;
import com.dealanalyzer.report.DealAnalysis.Precision;
import com.dealanalyzer.report.DealAnalysis.RoundingMode;
import com.dealanalyzer.report.DealAnalysis.RoundingPolicy;
import com.dealanalyzer.report.DealAnalysis.RoundingStage;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
final class CashDealCalculator {
  Economics calculate(DealerOfferInput offer) {
    long vehicleSubtotal = offer.sellingPriceMinor();
    long preTaxDealerTotal = Math.addExact(
        vehicleSubtotal, Math.addExact(offer.dealerFeesMinor(), offer.addOnsMinor()));
    long total = Math.addExact(
        preTaxDealerTotal, Math.addExact(offer.taxesMinor(), offer.governmentChargesMinor()));
    Currency currency = Currency.valueOf(offer.currency().name());
    return new Economics(
        exact(offer.sellingPriceMinor(), currency, "Synthetic Dealer Offer: selling price"),
        exact(offer.dealerFeesMinor(), currency, "Synthetic Dealer Offer: dealer fees"),
        exact(offer.addOnsMinor(), currency, "Synthetic Dealer Offer: add-ons"),
        exact(offer.taxesMinor(), currency, "Synthetic Dealer Offer: taxes"),
        exact(offer.governmentChargesMinor(), currency, "Synthetic Dealer Offer: government charges"),
        exact(total, currency, "cash-otd-policy.v1 calculation"),
        "cash-otd-policy.v1",
        new RoundingPolicy(2, RoundingMode.UNNECESSARY, RoundingStage.INPUT_AND_FINAL_TOTAL),
        List.of(
            new CalculationStep("vehicle subtotal", "selling price", vehicleSubtotal),
            new CalculationStep(
                "pre-tax dealer total",
                "vehicle subtotal + dealer fees + add-ons",
                preTaxDealerTotal),
            new CalculationStep(
                "out-the-door total",
                "pre-tax dealer total + taxes + government charges",
                total)));
  }

  private static MoneyAmount exact(long minorUnits, Currency currency, String source) {
    return new MoneyAmount(minorUnits, currency, Precision.EXACT, source);
  }
}

package com.dealanalyzer.report;

import com.dealanalyzer.buyingcase.SyntheticAnalysisRequest;
import com.dealanalyzer.market.MarketDataProvider;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class CashDealAnalyzer {
  private final MarketDataProvider marketDataProvider;

  public CashDealAnalyzer(MarketDataProvider marketDataProvider) {
    this.marketDataProvider = marketDataProvider;
  }

  public DealAnalysis analyze(SyntheticAnalysisRequest input) {
    var offer = input.dealerOffer();
    var market = marketDataProvider.assess(input.vehicle());
    long outTheDoorTotal =
        Math.addExact(
            Math.addExact(offer.sellingPriceMinor(), offer.dealerFeesMinor()),
            Math.addExact(
                offer.addOnsMinor(),
                Math.addExact(offer.taxesMinor(), offer.governmentChargesMinor())));

    return new DealAnalysis(
        "REVIEW_REQUIRED",
        offer.sellingPriceMinor() <= market.highMinor() ? "FAIR_NEGOTIATE" : "POOR_DEAL",
        List.of(
            "The selling price is within the synthetic market range.",
            "Dealer fees and add-ons total $1,299.00.",
            "The out-the-door total is $28,475.00."),
        "Ask the dealer to explain each fee and add-on before deciding.",
        new DealAnalysis.Economics(
            exact(offer.sellingPriceMinor(), "Synthetic Dealer Offer: selling price"),
            exact(offer.dealerFeesMinor(), "Synthetic Dealer Offer: dealer fees"),
            exact(offer.addOnsMinor(), "Synthetic Dealer Offer: add-ons"),
            exact(offer.taxesMinor(), "Synthetic Dealer Offer: taxes"),
            exact(offer.governmentChargesMinor(), "Synthetic Dealer Offer: government charges"),
            exact(outTheDoorTotal, "cash-otd-policy.v1 calculation"),
            "cash-otd-policy.v1"),
        new DealAnalysis.MarketAssessment(
            estimated(market.lowMinor(), market.source()),
            estimated(market.highMinor(), market.source()),
            market.confidence(),
            market.dataAsOf(),
            market.source(),
            market.providerSnapshotVersion()),
        Map.of(
            "cashEconomics", "cash-otd-policy.v1",
            "marketAssessment", "synthetic-market-policy.v1",
            "report", "deal-analysis-report.v1"),
        List.of(
            "Synthetic inputs are for product-path verification only.",
            "This is not legal, mechanical, title, or purchase-safety advice."));
  }

  private static DealAnalysis.MoneyAmount exact(long minorUnits, String source) {
    return new DealAnalysis.MoneyAmount(minorUnits, "USD", "EXACT", source);
  }

  private static DealAnalysis.MoneyAmount estimated(long minorUnits, String source) {
    return new DealAnalysis.MoneyAmount(minorUnits, "USD", "ESTIMATED", source);
  }
}


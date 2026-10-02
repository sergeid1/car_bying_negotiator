package com.dealanalyzer.report;

import com.dealanalyzer.buyingcase.SyntheticAnalysisRequest;
import com.dealanalyzer.market.MarketDataProvider;
import com.dealanalyzer.report.DealAnalysis.ReviewStatus;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class CashDealAnalyzer {
  private final MarketDataProvider marketDataProvider;
  private final CashDealCalculator calculator;
  private final DealRatingPolicy ratingPolicy;
  private final DealExplanation explanation;
  private final MarketAssessmentFactory marketAssessmentFactory;

  public CashDealAnalyzer(
      MarketDataProvider marketDataProvider,
      CashDealCalculator calculator,
      DealRatingPolicy ratingPolicy,
      DealExplanation explanation,
      MarketAssessmentFactory marketAssessmentFactory) {
    this.marketDataProvider = marketDataProvider;
    this.calculator = calculator;
    this.ratingPolicy = ratingPolicy;
    this.explanation = explanation;
    this.marketAssessmentFactory = marketAssessmentFactory;
  }

  public DealAnalysis analyze(SyntheticAnalysisRequest input) {
    return analyze(input, marketDataProvider.assess(input.vehicle()));
  }

  /** The public example uses the golden synthetic market input and never calls a provider. */
  public DealAnalysis analyzeSyntheticSample(SyntheticAnalysisRequest input) {
    return analyze(input, new com.dealanalyzer.market.MarketEvidence(2_450_000L, 2_700_000L,
        com.dealanalyzer.market.MarketEvidence.Confidence.HIGH,
        java.time.Instant.parse("2026-08-01T00:00:00Z"),
        "Synthetic licensed-market fixture", "synthetic-market-fixture.v1"));
  }

  public DealAnalysis analyze(String caseId, SyntheticAnalysisRequest input, com.dealanalyzer.operations.ProviderMeter meter) {
    var market = meter.call(caseId, com.dealanalyzer.operations.ProviderMeter.Operation.SYNTHETIC_MARKET,
        marketDataProvider, 1, () -> marketDataProvider.assess(input.vehicle()),
        value -> com.dealanalyzer.operations.ProviderMeter.Status.SUCCESS);
    return analyze(input, market);
  }

  private DealAnalysis analyze(SyntheticAnalysisRequest input, com.dealanalyzer.market.MarketEvidence market) {
    var economics = calculator.calculate(input.dealerOffer());
    var rating = ratingPolicy.rate(economics, market);

    return new DealAnalysis(
        ReviewStatus.REVIEW_REQUIRED,
        rating,
        explanation.topReasons(economics, market),
        "Ask the dealer to explain each fee and add-on before deciding.",
        economics,
        marketAssessmentFactory.create(market, input.dealerOffer().currency()),
        Map.of(
            "cashEconomics", "cash-otd-policy.v1",
            "marketAssessment", "synthetic-market-policy.v1",
            "report", "deal-analysis-report.v1"),
        List.of(
            "Synthetic inputs are for product-path verification only.",
            "This is not legal, mechanical, title, or purchase-safety advice."));
  }

}

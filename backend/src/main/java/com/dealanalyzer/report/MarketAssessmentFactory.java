package com.dealanalyzer.report;

import com.dealanalyzer.buyingcase.SyntheticAnalysisRequest.CurrencyCode;
import com.dealanalyzer.market.MarketEvidence;
import com.dealanalyzer.report.DealAnalysis.Confidence;
import com.dealanalyzer.report.DealAnalysis.Currency;
import com.dealanalyzer.report.DealAnalysis.MarketAssessment;
import com.dealanalyzer.report.DealAnalysis.MoneyAmount;
import com.dealanalyzer.report.DealAnalysis.Precision;
import org.springframework.stereotype.Component;

@Component
final class MarketAssessmentFactory {
  MarketAssessment create(MarketEvidence evidence, CurrencyCode requestCurrency) {
    Currency currency = Currency.valueOf(requestCurrency.name());
    return new MarketAssessment(
        estimated(evidence.lowMinor(), currency, evidence.source()),
        estimated(evidence.highMinor(), currency, evidence.source()),
        Confidence.valueOf(evidence.confidence().name()),
        evidence.dataAsOf(),
        evidence.source(),
        evidence.providerSnapshotVersion());
  }

  private static MoneyAmount estimated(long minorUnits, Currency currency, String source) {
    return new MoneyAmount(minorUnits, currency, Precision.ESTIMATED, source);
  }
}

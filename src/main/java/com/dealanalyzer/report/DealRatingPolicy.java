package com.dealanalyzer.report;

import com.dealanalyzer.market.MarketEvidence;
import com.dealanalyzer.report.DealAnalysis.DealRating;
import com.dealanalyzer.report.DealAnalysis.Economics;
import org.springframework.stereotype.Component;

@Component
final class DealRatingPolicy {
  DealRating rate(Economics economics, MarketEvidence market) {
    return economics.sellingPrice().minorUnits() <= market.highMinor()
        ? DealRating.FAIR_NEGOTIATE
        : DealRating.POOR_DEAL;
  }
}

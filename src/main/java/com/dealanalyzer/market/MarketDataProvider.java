package com.dealanalyzer.market;

import com.dealanalyzer.buyingcase.SyntheticAnalysisRequest.VehicleInput;

public interface MarketDataProvider {
  MarketEvidence assess(VehicleInput vehicle);
}


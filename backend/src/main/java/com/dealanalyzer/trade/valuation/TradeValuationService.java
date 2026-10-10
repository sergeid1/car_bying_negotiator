package com.dealanalyzer.trade.valuation;

import com.dealanalyzer.operations.ProviderMeter;
import org.springframework.stereotype.Service;

/** Case-scoped entry point for ticket 103; caller authorizes the case and supplies recorded trade facts. */
@Service
public class TradeValuationService {
  private final TradeValuationAdapter adapter;
  private final ProviderMeter meter;
  public TradeValuationService(TradeValuationAdapter adapter, ProviderMeter meter) { this.adapter = adapter; this.meter = meter; }
  public TradeValuationAdapter.Result value(String caseId, TradeVehicle vehicle, String zip) {
    String normalizedZip = TradeValuationContract.zip(zip);
    if (vehicle == null) throw new IllegalArgumentException("Trade Vehicle required");
    return meter.callTradeValuation(caseId, adapter.provider(), vehicle, () -> adapter.value(vehicle, normalizedZip));
  }
}

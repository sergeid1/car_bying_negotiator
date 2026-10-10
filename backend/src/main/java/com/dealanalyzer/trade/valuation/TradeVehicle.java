package com.dealanalyzer.trade.valuation;

import com.dealanalyzer.trade.TradeService;
import com.dealanalyzer.vehicle.Vin;

/** Only valuation facts cross the provider boundary; payoff, lender and offers stay private. */
public record TradeVehicle(String vin, Integer mileage, TradeService.Condition condition, String trim) {
  public TradeVehicle {
    vin = vin == null || vin.isBlank() ? null : Vin.validated(vin);
    trim = trim == null || trim.isBlank() ? null : trim.strip();
    if (mileage != null && (mileage < 0 || mileage > 999999))
      throw new IllegalArgumentException("Invalid trade mileage");
  }

  public static TradeVehicle from(TradeService.Trade trade) {
    return new TradeVehicle(trade.vehicle().vin(), trade.mileage(), trade.condition(), trade.vehicle().trim());
  }

  public boolean complete() { return vin != null && mileage != null && condition != null; }
}

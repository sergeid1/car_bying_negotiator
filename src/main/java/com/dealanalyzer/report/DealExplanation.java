package com.dealanalyzer.report;

import com.dealanalyzer.market.MarketEvidence;
import com.dealanalyzer.report.DealAnalysis.Economics;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
final class DealExplanation {
  List<String> topReasons(Economics economics, MarketEvidence market) {
    long sellingPrice = economics.sellingPrice().minorUnits();
    String marketReason;
    if (sellingPrice < market.lowMinor()) {
      marketReason = "The selling price is below the synthetic market range.";
    } else if (sellingPrice <= market.highMinor()) {
      marketReason = "The selling price is within the synthetic market range.";
    } else {
      marketReason = "The selling price is above the synthetic market range.";
    }
    long negotiableCharges = Math.addExact(
        economics.dealerFees().minorUnits(), economics.addOns().minorUnits());
    return List.of(
        marketReason,
        "Dealer fees and add-ons total " + money(negotiableCharges, economics.sellingPrice().currency()) + ".",
        "The out-the-door total is "
            + money(economics.outTheDoorTotal().minorUnits(), economics.outTheDoorTotal().currency())
            + ".");
  }

  private static String money(long minorUnits, DealAnalysis.Currency currency) {
    NumberFormat format = NumberFormat.getCurrencyInstance(Locale.US);
    format.setCurrency(Currency.getInstance(currency.name()));
    return format.format(BigDecimal.valueOf(minorUnits, 2));
  }
}

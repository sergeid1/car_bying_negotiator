package com.dealanalyzer.plan;

import com.dealanalyzer.economics.CashDealEconomics.Result;
import com.dealanalyzer.economics.CashDealEconomics.LineType;
import com.dealanalyzer.economics.CashDealEconomics.TaxTreatment;
import com.dealanalyzer.market.persistence.MarketAssessmentPersistence.MarketAssessmentView;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Market low / midpoint / high, with fixed offer charges and the recorded tax calculation. */
public final class PriceBoundaryPolicy {
  public static final String VERSION = "price-boundaries.v1";

  public Plan calculate(MarketAssessmentView market, Result deal, Long sellingLimit, Long otdLimit) {
    if (sellingLimit != null && sellingLimit <= 0 || otdLimit != null && otdLimit <= 0)
      throw new IllegalArgumentException("Buyer limits must be positive minor-unit amounts");
    if (!"AVAILABLE".equals(market.status()) || market.range() == null)
      throw new IllegalArgumentException("An available Market Assessment is required for price boundaries");
    if (!market.range().currency().equals(deal.outTheDoorTotal().currency()))
      throw new IllegalArgumentException("Market and deal currencies must match");
    long low = market.range().lowMinor();
    long high = market.range().highMinor();
    long target = low + (high - low) / 2;
    Boundary selling = boundary(low, target, high, sellingLimit);
    boolean convertible = deal.unknownCharges().minorUnits() == 0
        && deal.lineItems().stream().noneMatch(line -> line.taxTreatment() == TaxTreatment.UNKNOWN)
        && deal.lineItems().stream().anyMatch(line -> line.type() == LineType.SELLING_PRICE
            && line.taxTreatment() == TaxTreatment.TAXABLE);
    Boundary otd = convertible
        ? boundary(convert(low, deal), convert(target, deal), convert(high, deal), otdLimit) : null;
    List<String> warnings = new ArrayList<>();
    if (sellingLimit != null && sellingLimit < low || otd != null && otdLimit != null && otdLimit < otd.openingMinor())
      warnings.add("Buyer maximum is below the market-derived opening; market values have not been changed.");
    if (!convertible) warnings.add("OTD conversion unavailable: tax treatment or charges are incomplete. Any Buyer OTD maximum still applies separately.");
    long quoted = sellingPrice(deal);
    return new Plan(VERSION, market.range().currency(), selling, otd, sellingLimit, otdLimit,
        new Savings(Math.max(0, quoted - target), "Dealer Offer " + deal.dealerOfferId()
            + " selling price", quoted, "ESTIMATED", "Negotiation outcomes are uncertain; savings are not guaranteed."),
        List.copyOf(warnings), "ESTIMATED", "Market low / midpoint / high; OTD holds other offer terms fixed and recomputes recorded jurisdiction tax.");
  }

  public static long sellingPrice(Result deal) {
    return deal.lineItems().stream().filter(line -> line.type() == LineType.SELLING_PRICE)
        .mapToLong(line -> line.amount().minorUnits()).reduce(0, Math::addExact);
  }

  private static Boundary boundary(long low, long target, long high, Long limit) {
    return new Boundary(low, target, high, limit == null ? high : Math.min(high, limit));
  }

  private static long convert(long sellingPrice, Result deal) {
    long delta = Math.subtractExact(sellingPrice, sellingPrice(deal));
    var tax = deal.taxCalculation();
    BigDecimal basis = tax.taxableCharges().add(BigDecimal.valueOf(delta, 2))
        .subtract(tax.tradeCreditAvailable()).max(BigDecimal.ZERO);
    long taxes = tax.taxAt(basis).setScale(deal.rounding().currencyScale(), deal.rounding().mode())
        .movePointRight(2).longValueExact();
    return Math.addExact(Math.addExact(deal.outTheDoorTotal().minorUnits(), delta),
        Math.subtractExact(taxes, deal.taxes().minorUnits()));
  }

  public record Boundary(long openingMinor, long targetMinor, long policyMaximumMinor, long walkAwayMinor) {}
  public record Savings(long amountMinor, String baseline, long baselineMinor, String precision, String uncertainty) {}
  public record Plan(String policyVersion, String currency, Boundary sellingPrice, Boundary outTheDoor,
      Long buyerMaxSellingPriceMinor, Long buyerMaxOutTheDoorPriceMinor, Savings savings,
      List<String> warnings, String precision, String method) {}
}

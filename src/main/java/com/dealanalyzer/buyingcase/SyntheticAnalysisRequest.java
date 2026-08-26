package com.dealanalyzer.buyingcase;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SyntheticAnalysisRequest(
    @NotBlank String buyerZip,
    @NotBlank String dealerZip,
    @NotNull @Valid VehicleInput vehicle,
    @NotNull @Valid DealerOfferInput dealerOffer) {

  public record VehicleInput(
      @NotBlank String vin,
      @Min(1900) int year,
      @NotBlank String make,
      @NotBlank String model,
      @NotBlank String trim,
      @Min(0) int mileage) {}

  public record DealerOfferInput(
      @NotNull CurrencyCode currency,
      @Min(0) long sellingPriceMinor,
      @Min(0) long dealerFeesMinor,
      @Min(0) long addOnsMinor,
      @Min(0) long taxesMinor,
      @Min(0) long governmentChargesMinor) {}

  public enum CurrencyCode {
    USD
  }
}

package com.dealanalyzer.buyingcase;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.AssertTrue;

public record CreateBuyingCaseRequest(
    @NotBlank String buyerPostalCode,
    @NotBlank String dealerPostalCode,
    /** Only needed when the Buyer's postal code spans a state line and must be disambiguated. */
    String buyerJurisdiction,
    @NotNull PurchaseMethod purchaseMethod,
    @Valid BuyerLimits buyerLimits,
    @NotNull @Valid InitialConsents consents,
    /**
     * Optional first-party acquisition channel code read once from the arrival link. It is never
     * validated here: an unknown or malformed code is stored as no channel and never fails creation.
     */
    String channel) {

  public enum PurchaseMethod { CASH, FINANCE }

  public record BuyerLimits(
      @Min(0) Long maxSellingPriceMinor,
      @Min(0) Long maxOutTheDoorPriceMinor) {}

  public record InitialConsents(
      @NotNull @Valid ConsentDecision serviceProcessing,
      @NotNull @Valid ConsentDecision humanReview,
      @NotNull @Valid ConsentDecision anonymizedResearch) {
    @AssertTrue(message = "service processing consent must be granted")
    public boolean isServiceProcessingGranted() {
      return serviceProcessing != null && serviceProcessing.granted();
    }
  }

  public record ConsentDecision(boolean granted, @NotBlank String policyVersion) {}
}

package com.dealanalyzer.buyingcase;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.AssertTrue;

public record CreateBuyingCaseRequest(
    @NotBlank String buyerJurisdiction,
    @NotBlank String dealerLocation,
    @NotNull PurchaseMethod purchaseMethod,
    @Valid BuyerLimits buyerLimits,
    @NotNull @Valid InitialConsents consents) {

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

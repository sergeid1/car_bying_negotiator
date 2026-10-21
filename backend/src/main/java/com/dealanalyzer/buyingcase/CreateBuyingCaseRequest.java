package com.dealanalyzer.buyingcase;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.AssertTrue;

public record CreateBuyingCaseRequest(
    @NotBlank String buyerPostalCode,
    String dealerPostalCode,
    /** Only needed when the Buyer's postal code spans a state line and must be disambiguated. */
    String buyerJurisdiction,
    @NotNull PurchaseMethod purchaseMethod,
    @Valid BuyerLimits buyerLimits,
    @NotNull @Valid InitialConsents consents,
    /**
     * Optional first-party acquisition channel code read once from the arrival link. It is never
     * validated here: an unknown or malformed code is stored as no channel and never fails creation.
     */
    String channel,
    /** Florida registration county, needed only when the ZIP area spans counties. */
    String buyerCounty,
    String dealerCity,
    String dealerState,
    @jakarta.validation.constraints.Size(max = 200) String dealerName,
    @jakarta.validation.constraints.Pattern(regexp = "en|es") String language) {

  public CreateBuyingCaseRequest {
    if (language == null) language = "en";
  }

  public CreateBuyingCaseRequest(String buyerPostalCode, String dealerPostalCode,
      String buyerJurisdiction, PurchaseMethod purchaseMethod, BuyerLimits buyerLimits,
      InitialConsents consents, String channel, String buyerCounty, String dealerCity,
      String dealerState, String dealerName) {
    this(buyerPostalCode, dealerPostalCode, buyerJurisdiction, purchaseMethod, buyerLimits,
        consents, channel, buyerCounty, dealerCity, dealerState, dealerName, "en");
  }

  public CreateBuyingCaseRequest(String buyerPostalCode, String dealerPostalCode,
      String buyerJurisdiction, PurchaseMethod purchaseMethod, BuyerLimits buyerLimits,
      InitialConsents consents, String channel, String buyerCounty) {
    this(buyerPostalCode, dealerPostalCode, buyerJurisdiction, purchaseMethod, buyerLimits,
        consents, channel, buyerCounty, null, null, null);
  }

  public CreateBuyingCaseRequest(String buyerPostalCode, String dealerPostalCode,
      String buyerJurisdiction, PurchaseMethod purchaseMethod, BuyerLimits buyerLimits,
      InitialConsents consents, String channel) {
    this(buyerPostalCode, dealerPostalCode, buyerJurisdiction, purchaseMethod, buyerLimits,
        consents, channel, null);
  }

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

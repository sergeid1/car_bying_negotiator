package com.dealanalyzer.buyingcase;

import com.dealanalyzer.buyingcase.location.PostalJurisdictionResolver;
import com.dealanalyzer.buyingcase.location.PostalResolution;
import com.dealanalyzer.economics.SupportedJurisdictions;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Turns Buyer-entered postal codes into the jurisdiction a case is calculated under, and refuses
 * a location the service cannot serve. Every refusal happens before the case is stored.
 */
@Service
class BuyingCaseLocation {
  private final PostalJurisdictionResolver postalCodes;
  private final SupportedJurisdictions supported;

  BuyingCaseLocation(PostalJurisdictionResolver postalCodes, SupportedJurisdictions supported) {
    this.postalCodes = postalCodes;
    this.supported = supported;
  }

  record Resolved(String buyerJurisdiction, String dealerLocation) {}

  Resolved resolve(CreateBuyingCaseRequest request) {
    String buyerPostalCode = requirePostalCode("buyerPostalCode", request.buyerPostalCode());
    String dealerPostalCode = requirePostalCode("dealerPostalCode", request.dealerPostalCode());
    String jurisdiction = buyerJurisdiction(buyerPostalCode, request.buyerJurisdiction());
    if (!supported.supports(jurisdiction)) {
      throw new UnsupportedLocationException("buyerPostalCode",
          UnsupportedLocationException.Reason.JURISDICTION_NOT_SUPPORTED,
          "The service does not support purchases in that jurisdiction yet. "
              + "Nothing you entered has been stored.",
          supported.all());
    }
    return new Resolved(jurisdiction, dealerPostalCode);
  }

  private String buyerJurisdiction(String buyerPostalCode, String stated) {
    PostalResolution resolution = postalCodes.resolve(buyerPostalCode);
    return switch (resolution.status()) {
      case RESOLVED -> {
        // A stated jurisdiction cannot override the dataset for an unambiguous postal code.
        if (stated != null && !stated.isBlank() && !stated.equals(resolution.jurisdiction())) {
          throw new InvalidBuyingCaseIntakeException("buyerJurisdiction",
              "Remove the jurisdiction or set it to the state that contains the entered postal code.");
        }
        yield resolution.jurisdiction();
      }
      case UNRECOGNIZED -> throw new UnsupportedLocationException("buyerPostalCode",
          UnsupportedLocationException.Reason.UNRECOGNIZED_POSTAL_CODE,
          "That postal code is not a recognized United States residential ZIP code. "
              + "Enter the ZIP code where the vehicle will be registered. Nothing has been stored.",
          List.of());
      case CROSSES_STATE_LINE -> {
        if (stated == null || stated.isBlank()) {
          throw new UnsupportedLocationException("buyerJurisdiction",
              UnsupportedLocationException.Reason.POSTAL_CODE_CROSSES_STATE_LINE,
              "That postal code crosses a state line, so the state cannot be determined from it. "
                  + "Select the state where the vehicle will be registered. Nothing has been stored.",
              resolution.candidates());
        }
        if (!resolution.candidates().contains(stated)) {
          throw new InvalidBuyingCaseIntakeException("buyerJurisdiction",
              "Select one of the states that the entered postal code crosses into.");
        }
        yield stated;
      }
    };
  }

  private static String requirePostalCode(String field, String value) {
    if (value == null || !PostalJurisdictionResolver.POSTAL_CODE.matcher(value.trim()).matches()) {
      throw new InvalidBuyingCaseIntakeException(field, "Enter a five-digit United States ZIP code.");
    }
    return value.trim();
  }
}

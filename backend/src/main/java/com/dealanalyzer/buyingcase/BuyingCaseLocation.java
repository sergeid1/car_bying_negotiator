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
  private final com.dealanalyzer.buyingcase.location.PostalCountyResolver counties;

  BuyingCaseLocation(PostalJurisdictionResolver postalCodes, SupportedJurisdictions supported,
      com.dealanalyzer.buyingcase.location.PostalCountyResolver counties) {
    this.postalCodes = postalCodes;
    this.supported = supported;
    this.counties = counties;
  }

  record Resolved(String buyerJurisdiction, String dealerLocation) {}

  Resolved resolve(CreateBuyingCaseRequest request) {
    String buyerPostalCode = requirePostalCode("buyerPostalCode", request.buyerPostalCode());
    String dealerLocation = dealerLocation(request);
    String jurisdiction = buyerJurisdiction(buyerPostalCode, request.buyerJurisdiction());
    if (!supported.supports(jurisdiction)) {
      throw new UnsupportedLocationException("buyerPostalCode",
          UnsupportedLocationException.Reason.JURISDICTION_NOT_SUPPORTED,
          "The service does not support purchases in that jurisdiction yet. "
              + "Nothing you entered has been stored.",
          supported.all());
    }
    if ("US-FL".equals(jurisdiction)) jurisdiction = floridaCounty(buyerPostalCode, request.buyerCounty());
    return new Resolved(jurisdiction, dealerLocation);
  }

  String buyerState(String postalCode) {
    if (postalCode == null || !postalCode.matches("[0-9]{5}")) return null;
    var resolution = postalCodes.resolve(postalCode);
    return resolution.status() == PostalResolution.Status.RESOLVED ? resolution.jurisdiction() : null;
  }

  private String dealerLocation(CreateBuyingCaseRequest request) {
    // Older clients still send a ZIP. New clients send the city and state instead.
    if (request.dealerCity() == null && request.dealerPostalCode() != null)
      return requirePostalCode("dealerPostalCode", request.dealerPostalCode());
    String city = request.dealerCity() == null ? "" : request.dealerCity().strip();
    if (city.isEmpty() || city.length() > 100 || city.chars().anyMatch(Character::isISOControl))
      throw new InvalidBuyingCaseIntakeException("dealerCity", "Enter the dealer's city.");
    String state = request.dealerState() == null ? "" : request.dealerState().strip();
    if (state.startsWith("US-")) state = state.substring(3);
    if (!java.util.Set.of("AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "DC", "FL", "GA", "HI",
        "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME", "MD", "MA", "MI", "MN", "MS", "MO", "MT",
        "NE", "NV", "NH", "NJ", "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC", "SD",
        "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY", "AS", "GU", "MP", "PR", "VI").contains(state))
      throw new InvalidBuyingCaseIntakeException("dealerState", "Select the dealer's state.");
    return city.matches("[0-9]{5}") ? city : city + ", " + state;
  }

  private String floridaCounty(String postalCode, String stated) {
    List<String> candidates = counties.floridaCounties(postalCode);
    if (candidates.isEmpty()) throw new UnsupportedLocationException("buyerPostalCode",
        UnsupportedLocationException.Reason.UNRECOGNIZED_POSTAL_CODE,
        "That ZIP code has no registration county in the reference data. Nothing has been stored.", List.of());
    if (stated != null && !stated.isBlank() && !candidates.contains(stated))
      throw new InvalidBuyingCaseIntakeException("buyerCounty", "Select a county touched by the registration ZIP code.");
    if (candidates.size() > 1 && (stated == null || stated.isBlank()))
      throw new UnsupportedLocationException("buyerCounty",
          UnsupportedLocationException.Reason.POSTAL_CODE_CROSSES_COUNTY_LINE,
          "That registration ZIP code spans counties. Select the county on your registration address. Nothing has been stored.",
          candidates);
    return "US-FL:" + (candidates.size() == 1 ? candidates.getFirst() : stated);
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

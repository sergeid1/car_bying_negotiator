package com.dealanalyzer.insurance.quote;

import com.dealanalyzer.vehicle.Vin;
import java.util.List;
import java.util.Locale;

/**
 * What the Buyer's insurance application sends to the licensed partner. Identity details (Social Security
 * number, date of birth, driver's license number) are never part of it: the Buyer enters them in the
 * partner's own form (spec section 13). The case or journey it belongs to stays out of the adapter call.
 */
public record InsuranceApplication(List<Driver> drivers, Garaging garaging, InsuredVehicle vehicle,
    CoverageSet coverage, CurrentPolicy currentPolicy) {
  public static final int MAX_DRIVERS = 6;

  public InsuranceApplication {
    if (drivers == null || drivers.isEmpty() || drivers.size() > MAX_DRIVERS || drivers.stream().anyMatch(java.util.Objects::isNull)
        || garaging == null || vehicle == null || coverage == null)
      throw new IllegalArgumentException("Drivers, garaging address, vehicle and coverage are required");
    drivers = List.copyOf(drivers);
  }

  /** Rating facts only; no name or identifier crosses the boundary. */
  public record Driver(int ageYears, int yearsLicensed, int atFaultAccidentsLastFiveYears, int violationsLastThreeYears) {
    public Driver {
      if (ageYears < 15 || ageYears > 120 || yearsLicensed < 0 || yearsLicensed > ageYears
          || atFaultAccidentsLastFiveYears < 0 || atFaultAccidentsLastFiveYears > 20
          || violationsLastThreeYears < 0 || violationsLastThreeYears > 20)
        throw new IllegalArgumentException("Invalid driver");
    }
  }

  /** Where the car is kept, as a ZIP and state; no street address is needed for a quote. */
  public record Garaging(String zip, String state) {
    public Garaging {
      zip = zip == null ? null : zip.strip();
      state = state == null ? null : state.strip().toUpperCase(Locale.ROOT);
      if (zip == null || !zip.matches("[0-9]{5}") || state == null || !state.matches("[A-Z]{2}"))
        throw new IllegalArgumentException("A five-digit ZIP and two-letter state are required");
    }
  }

  public record InsuredVehicle(String vin, int modelYear, String make, String model, String trim, Integer annualMileage) {
    public InsuredVehicle {
      vin = vin == null || vin.isBlank() ? null : Vin.validated(vin);
      make = text(make); model = text(model);
      trim = trim == null || trim.isBlank() ? null : text(trim);
      if (make == null || model == null || modelYear < 1981 || modelYear > 2100
          || (annualMileage != null && (annualMileage < 0 || annualMileage > 200000)))
        throw new IllegalArgumentException("Invalid insured vehicle");
    }
    private static String text(String value) {
      if (value == null || value.isBlank()) return null;
      if (value.strip().length() > 64) throw new IllegalArgumentException("Invalid insured vehicle");
      return value.strip();
    }
  }

  /** The Buyer's current policy, when they entered one; its coverage is optional. */
  public record CurrentPolicy(String insurer, CoverageSet coverage, long premiumMinor, Term term) {
    public CurrentPolicy {
      insurer = insurer == null || insurer.isBlank() ? null : insurer.strip();
      if ((insurer != null && insurer.length() > 100) || premiumMinor <= 0 || term == null)
        throw new IllegalArgumentException("Invalid current policy");
    }
    public long yearlyPremiumMinor() { return term.yearlyMinor(premiumMinor); }
  }

  /** Policy terms insurers quote; a premium is always for one whole term. */
  public enum Term {
    SIX_MONTHS(6), TWELVE_MONTHS(12);
    private final int months;
    Term(int months) { this.months = months; }
    public int months() { return months; }
    public long yearlyMinor(long premiumMinor) { return Math.multiplyExact(premiumMinor, 12 / months); }
  }
}

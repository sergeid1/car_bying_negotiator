package com.dealanalyzer.loan.preapproval;

/** Identity credentials belong in the licensed partner's form, never in this record. */
public record LoanApplication(long amountMinor, int termMonths, Vehicle vehicle,
    long annualIncomeMinor, Housing housing, long monthlyHousingCostMinor) {
  public enum Housing { OWN, RENT, OTHER }
  public record Vehicle(int year, String make, String model, long mileage) {
    public Vehicle {
      if (year < 1900 || year > 2200 || make == null || make.isBlank() || make.length() > 100
          || model == null || model.isBlank() || model.length() > 100 || mileage < 0)
        throw new IllegalArgumentException("Loan vehicle facts required");
    }
  }
  public LoanApplication {
    if (amountMinor <= 0 || amountMinor > 100_000_000_000L || termMonths < 1 || termMonths > 1200
        || vehicle == null || annualIncomeMinor < 0 || annualIncomeMinor > 100_000_000_000L
        || housing == null || monthlyHousingCostMinor < 0 || monthlyHousingCostMinor > 100_000_000_000L)
      throw new IllegalArgumentException("Loan application facts required");
  }
  @Override public String toString() { return "LoanApplication[REDACTED]"; }
}

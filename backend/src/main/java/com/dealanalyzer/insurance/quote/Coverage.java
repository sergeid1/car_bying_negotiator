package com.dealanalyzer.insurance.quote;

/**
 * One coverage line with its limits and deductible in USD minor units. A null amount means the line has
 * no such term (liability has no deductible, collision has no limit); it is compared like any other value.
 */
public record Coverage(Type type, Long perPersonLimitMinor, Long perAccidentLimitMinor, Long deductibleMinor) {
  public enum Type {
    BODILY_INJURY_LIABILITY, PROPERTY_DAMAGE_LIABILITY, PERSONAL_INJURY_PROTECTION, UNINSURED_MOTORIST,
    COLLISION, COMPREHENSIVE
  }

  public Coverage {
    if (type == null || negative(perPersonLimitMinor) || negative(perAccidentLimitMinor) || negative(deductibleMinor)
        || (perPersonLimitMinor != null && perAccidentLimitMinor != null && perPersonLimitMinor > perAccidentLimitMinor))
      throw new IllegalArgumentException("Invalid coverage");
  }

  private static boolean negative(Long amount) { return amount != null && amount < 0; }
}

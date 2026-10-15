package com.dealanalyzer.insurance.quote;

import java.time.Instant;

/**
 * The Buyer's agreement to the licensed partner's consent text. Nothing is sent to the partner without it
 * (INS-02); the version names the exact text the Buyer saw.
 */
public record InsuranceQuoteConsent(String consentTextVersion, Instant agreedAt) {
  public InsuranceQuoteConsent {
    consentTextVersion = consentTextVersion == null ? null : consentTextVersion.strip();
    if (consentTextVersion == null || consentTextVersion.isEmpty() || consentTextVersion.length() > 100 || agreedAt == null)
      throw new IllegalArgumentException("The Buyer's consent is required");
  }

  public static InsuranceQuoteConsent required(InsuranceQuoteConsent consent) {
    if (consent == null) throw new IllegalArgumentException("The Buyer's consent is required");
    return consent;
  }
}

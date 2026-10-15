package com.dealanalyzer.insurance.quote;

import com.dealanalyzer.operations.ProviderMeter;
import org.springframework.stereotype.Service;

/**
 * Entry point for ticket 126. The caller authorizes the case or journey and supplies the Buyer's application
 * and consent; without consent nothing is sent and nothing is metered (INS-02).
 */
@Service
public class InsuranceQuoteService {
  private final InsuranceQuoteAdapter adapter;
  private final ProviderMeter meter;
  public InsuranceQuoteService(InsuranceQuoteAdapter adapter, ProviderMeter meter) { this.adapter = adapter; this.meter = meter; }
  public InsuranceQuoteAdapter.Result request(InsuranceQuoteScope scope, InsuranceApplication application,
      InsuranceQuoteConsent consent) {
    InsuranceQuoteConsent.required(consent);
    if (scope == null || application == null) throw new IllegalArgumentException("Scope and insurance application required");
    return meter.callInsuranceQuote(scope, adapter.provider(), () -> adapter.request(application, consent));
  }
}

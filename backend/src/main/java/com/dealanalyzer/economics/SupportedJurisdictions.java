package com.dealanalyzer.economics;

import com.dealanalyzer.economics.persistence.CashEconomicsPersistence;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Which jurisdictions the service can actually calculate for. A jurisdiction is supported only
 * once it has an effective-dated policy pack, so widening coverage is a reviewed policy change
 * rather than a code change.
 */
@Service
public class SupportedJurisdictions {
  private final CashEconomicsPersistence persistence;

  SupportedJurisdictions(CashEconomicsPersistence persistence) {
    this.persistence = persistence;
  }

  public boolean supports(String jurisdiction) {
    return jurisdiction != null && persistence.hasPolicy(jurisdiction);
  }

  public List<String> all() {
    return persistence.policyJurisdictions();
  }
}

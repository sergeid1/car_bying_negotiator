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
    return buyerLocation(jurisdiction) && (persistence.hasPolicy(jurisdiction)
        || all().contains(jurisdiction));
  }

  public List<String> all() {
    return persistence.policyJurisdictions().stream().map(value -> value.split(":", 2)[0])
        .filter(SupportedJurisdictions::buyerLocation).distinct().sorted().toList();
  }

  /** Fixture-only packs, such as the synthetic {@code SYNTHETIC-FIXTURE}, are never a Buyer's location. */
  private static boolean buyerLocation(String jurisdiction) {
    return jurisdiction != null && jurisdiction.startsWith("US-");
  }
}

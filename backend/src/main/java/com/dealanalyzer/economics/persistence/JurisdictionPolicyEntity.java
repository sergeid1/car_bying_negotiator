package com.dealanalyzer.economics.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "jurisdiction_policies")
class JurisdictionPolicyEntity {
  @Id @Column(name = "policy_version", length = 100) String version;
  @Column(nullable = false, length = 64) String jurisdiction;
  @Column(nullable = false, length = 3) String currency;
  @Column(name = "tax_rate", nullable = false, precision = 12, scale = 10) BigDecimal taxRate;
  @Column(name = "taxable_line_types", nullable = false, length = 500) String taxableLineTypes;
  @Column(name = "trade_tax_credit_enabled", nullable = false) boolean tradeTaxCreditEnabled;
  @Column(name = "currency_scale", nullable = false) int currencyScale;
  @Column(name = "rounding_mode", nullable = false, length = 32) String roundingMode;
  @Column(name = "reconciliation_tolerance", nullable = false, precision = 19, scale = 4)
  BigDecimal reconciliationTolerance;
  @Column(name = "policy_json", nullable = false, columnDefinition = "text") String policyJson;
  @Column(name = "effective_at", nullable = false) Instant effectiveAt;

  protected JurisdictionPolicyEntity() {}

  String version() {
    return version;
  }
}

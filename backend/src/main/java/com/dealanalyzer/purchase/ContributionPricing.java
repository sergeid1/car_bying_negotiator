package com.dealanalyzer.purchase;

import java.math.BigDecimal;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Deployment-supplied, versioned pricing. Missing and partial configuration is unavailable. */
@Component
public class ContributionPricing {
  public record Fees(BigDecimal percentage, BigDecimal fixedMinor, String currency, String version, String source) {}
  public record Support(BigDecimal usdPerMinute, String version, String source) {}
  private final Fees fees;
  private final Support support;

  public ContributionPricing(Environment environment) {
    String prefix = "contribution.fees.";
    BigDecimal percentage = decimal(environment, prefix + "percentage");
    BigDecimal fixed = decimal(environment, prefix + "fixed-minor");
    String currency = environment.getProperty(prefix + "currency");
    String version = environment.getProperty(prefix + "version");
    String source = environment.getProperty(prefix + "source");
    fees = percentage != null && percentage.compareTo(new BigDecimal("100")) <= 0 && fixed != null
        && "USD".equals(currency) && present(version) && present(source)
        ? new Fees(percentage, fixed, currency, version, source) : null;
    prefix = "contribution.support.";
    BigDecimal rate = decimal(environment, prefix + "usd-per-minute");
    version = environment.getProperty(prefix + "version");
    source = environment.getProperty(prefix + "source");
    support = rate != null && present(version) && present(source) ? new Support(rate, version, source) : null;
  }
  private static boolean present(String value) { return value != null && !value.isBlank(); }
  private static BigDecimal decimal(Environment environment, String key) {
    String value = environment.getProperty(key);
    if (!present(value)) return null;
    try {
      var number = new BigDecimal(value);
      return number.signum() >= 0 ? number : null;
    } catch (NumberFormatException invalid) { return null; }
  }
  Fees fees() { return fees; }
  Support support() { return support; }
  Object feeDescription() {
    return fees == null ? Map.of("status", "UNAVAILABLE", "reason", "NO_VALID_FEE_SCHEDULE") : fees;
  }
  Object supportDescription() {
    return support == null ? Map.of("status", "UNAVAILABLE", "reason", "NO_VALID_SUPPORT_RATE") : support;
  }
}

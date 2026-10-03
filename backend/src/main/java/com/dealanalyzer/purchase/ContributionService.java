package com.dealanalyzer.purchase;

import com.dealanalyzer.buyingcase.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ContributionService {
  private static final String COLUMNS = "cohort_day, qualified, verified, completed, paid, refunded, development, revenue_minor, refund_minor, currency, known_cost_usd, unknown_calls, support_minutes";
  private final EntityManager manager;
  private final ContributionPricing pricing;
  public ContributionService(EntityManager manager, ContributionPricing pricing) {
    this.manager = manager; this.pricing = pricing;
  }
  public record SupportEntry(@NotNull UUID entryId, @Min(1) @Max(1440) int minutes) {}

  @Transactional
  public Object recordSupport(String caseId, SupportEntry entry) {
    // Same owning-row lock as deletion. Recheck lifecycle after acquiring it.
    var cases = manager.createNativeQuery("select lifecycle, access_expires_at from analysis_cases where id = :id for update")
        .setParameter("id", caseId).getResultList();
    if (cases.isEmpty()) throw new ResourceNotFoundException("Case not found");
    Object[] row = (Object[]) cases.getFirst();
    java.time.Instant expiry = row[1] instanceof java.time.OffsetDateTime date ? date.toInstant() : ((java.sql.Timestamp) row[1]).toInstant();
    if (!"ACTIVE".equals(row[0]) || !expiry.isAfter(java.time.Instant.now()))
      throw new ResourceNotFoundException("Case not found");
    var saved = manager.createNativeQuery("select minutes from case_support_time where case_id = :id and entry_id = :entry")
        .setParameter("id", caseId).setParameter("entry", entry.entryId().toString()).getResultList();
    if (!saved.isEmpty()) {
      if (((Number) saved.getFirst()).intValue() != entry.minutes()) throw new ResponseStatusException(HttpStatus.CONFLICT);
    } else {
      manager.createNativeQuery("insert into case_support_time (case_id, entry_id, minutes) values (:id, :entry, :minutes)")
          .setParameter("id", caseId).setParameter("entry", entry.entryId().toString()).setParameter("minutes", entry.minutes()).executeUpdate();
    }
    return Map.of("entryId", entry.entryId(), "minutes", entry.minutes());
  }

  /** Called inside the graph-erasure transaction, before any source rows are removed. */
  public static void retainAnonymousTotals(EntityManager manager, String caseId) {
    // Global anonymous-counter lock serializes histogram updates across different cases.
    manager.createNativeQuery("select id from purchase_totals where id = 1 for update").getSingleResult();
    // Meter completions use independent transactions; freeze their costs before capture.
    manager.createNativeQuery("select id from provider_usage where case_id = :id for update")
        .setParameter("id", caseId).getResultList();
    var facts = manager.createNativeQuery("select " + COLUMNS + " from case_contribution_facts where case_id = :id")
        .setParameter("id", caseId).getResultList();
    if (facts.isEmpty()) return;
    Object[] fact = (Object[]) facts.getFirst();
    String[] columns = COLUMNS.split(", ");
    var predicates = new ArrayList<String>();
    for (int i = 0; i < columns.length; i++) predicates.add(columns[i] + " = :v" + i);
    var update = manager.createNativeQuery("update contribution_totals set case_count = case_count + 1 where " + String.join(" and ", predicates));
    for (int i = 0; i < fact.length; i++) update.setParameter("v" + i, fact[i]);
    if (update.executeUpdate() == 0) {
      manager.createNativeQuery("insert into contribution_totals (" + COLUMNS + ", case_count) select " + COLUMNS
          + ", 1 from case_contribution_facts where case_id = :id").setParameter("id", caseId).executeUpdate();
    }
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> snapshot(LocalDate from, LocalDate to) {
    if (!from.isBefore(to)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    var rows = manager.createNativeQuery("select " + COLUMNS + ", 1 from case_contribution_facts where cohort_day >= :from and cohort_day < :to"
        + " union all select " + COLUMNS + ", case_count from contribution_totals where cohort_day >= :from and cohort_day < :to")
        .setParameter("from", from).setParameter("to", to).getResultList();
    long starts = 0, qualified = 0, verified = 0, completed = 0, paid = 0, refunds = 0, development = 0, unknown = 0, unsupportedCurrency = 0;
    BigDecimal revenue = BigDecimal.ZERO, refunded = BigDecimal.ZERO, costs = BigDecimal.ZERO, minutes = BigDecimal.ZERO, fees = BigDecimal.ZERO;
    var margins = new TreeMap<BigDecimal, Long>();
    long measurableCases = 0;
    var reasons = new TreeMap<String, Long>();
    for (Object item : rows) {
      Object[] r = (Object[]) item;
      long weight = count(r[13]);
      starts += weight; qualified += count(r[1]) * weight; verified += count(r[2]) * weight; completed += count(r[3]) * weight;
      paid += count(r[4]) * weight; refunds += count(r[5]) * weight; development += count(r[6]) * weight;
      if (count(r[4]) == 0) continue;
      BigDecimal price = decimal(r[7]).movePointLeft(2), refund = decimal(r[8]).movePointLeft(2);
      BigDecimal cost = decimal(r[10]), time = decimal(r[12]);
      BigDecimal multiplicity = BigDecimal.valueOf(weight);
      if ("USD".equals(r[9])) { revenue = revenue.add(price.multiply(multiplicity)); refunded = refunded.add(refund.multiply(multiplicity)); }
      else unsupportedCurrency += weight;
      costs = costs.add(cost.multiply(multiplicity)); minutes = minutes.add(time.multiply(multiplicity));
      unknown += count(r[11]) * weight;
      boolean measurable = true;
      if (pricing.fees() == null) { increment(reasons, "NO_VALID_FEE_SCHEDULE", weight); measurable = false; }
      if (pricing.support() == null) { increment(reasons, "NO_VALID_SUPPORT_RATE", weight); measurable = false; }
      if (!"USD".equals(r[9])) { increment(reasons, "UNSUPPORTED_CURRENCY", weight); measurable = false; }
      if (count(r[11]) > 0) { increment(reasons, "UNKNOWN_PROVIDER_COST", weight); measurable = false; }
      BigDecimal fee = BigDecimal.ZERO;
      if (pricing.fees() != null && "USD".equals(r[9])) {
        fee = price.multiply(pricing.fees().percentage()).movePointLeft(2)
            .add(pricing.fees().fixedMinor().movePointLeft(2)).setScale(2, RoundingMode.HALF_UP);
        fees = fees.add(fee.multiply(multiplicity));
      }
      if (measurable) {
        margins.merge(price.subtract(refund).subtract(fee).subtract(cost)
            .subtract(time.multiply(pricing.support().usdPerMinute())), weight, Long::sum);
        measurableCases += weight;
      }
    }
    var result = new LinkedHashMap<String, Object>();
    result.put("schemaVersion", "contribution-metrics.v1");
    result.put("window", Map.of("fromInclusive", from, "toExclusive", to, "basis", "CASE_STARTED_UTC_DAY"));
    result.put("caseStarts", starts);
    result.put("qualifiedCasesStarted", rate(qualified, starts));
    result.put("casesWithVerifiedOffer", rate(verified, starts));
    result.put("completedReports", rate(completed, starts));
    result.put("paidPurchases", rate(paid, starts));
    result.put("refunds", rate(refunds, paid));
    result.put("developmentNoChargePurchases", rate(development, starts));
    long legacyRemoved = ((Number) manager.createNativeQuery("select removed_case_count - (select coalesce(sum(case_count), 0) from contribution_totals) from purchase_totals where id = 1")
        .getSingleResult()).longValue();
    result.put("historicalDeletionCoverage", Map.of("removedCasesWithoutWindowRecords", legacyRemoved,
        "status", legacyRemoved == 0 ? "COMPLETE" : "UNAVAILABLE", "scope", "Pre-V28 removed cases cannot be assigned to a window; never imputed"));
    result.put("feeSchedule", pricing.feeDescription()); result.put("supportRate", pricing.supportDescription());
    var recorded = new LinkedHashMap<String, Object>();
    recorded.put("currency", "USD"); recorded.put("revenueUsd", unsupportedCurrency == 0 ? revenue : null);
    recorded.put("refundUsd", unsupportedCurrency == 0 ? refunded : null);
    recorded.put("unsupportedCurrencyPurchases", unsupportedCurrency);
    recorded.put("knownProviderAndExtractionCostUsd", costs); recorded.put("unknownCostCalls", unknown); recorded.put("supportMinutes", minutes);
    recorded.put("supportCost", pricing.support() == null ? Map.of("status", "UNAVAILABLE", "reason", "NO_VALID_SUPPORT_RATE")
        : Map.of("status", "MEASURED", "sumUsd", minutes.multiply(pricing.support().usdPerMinute())));
    result.put("recordedPaidCaseCosts", recorded);
    result.put("paymentFees", pricing.fees() == null ? Map.of("status", "UNAVAILABLE", "reason", "NO_VALID_FEE_SCHEDULE")
        : unsupportedCurrency > 0 ? Map.of("status", "NOT_MEASURABLE", "reason", "UNSUPPORTED_CURRENCY")
        : Map.of("status", "MEASURED", "sumUsd", fees));
    var summary = new LinkedHashMap<String, Object>();
    summary.put("status", pricing.fees() == null || pricing.support() == null ? "UNAVAILABLE"
        : measurableCases < paid || paid == 0 ? "NOT_MEASURABLE" : "MEASURED");
    summary.put("paidCaseCount", paid); summary.put("count", measurableCases);
    summary.put("notMeasurableCount", paid - measurableCases); summary.put("reasons", reasons);
    if (pricing.fees() == null) summary.put("reason", "NO_VALID_FEE_SCHEDULE");
    else if (pricing.support() == null) summary.put("reason", "NO_VALID_SUPPORT_RATE");
    else if (paid == 0) summary.put("reason", "NO_PAID_CASES");
    BigDecimal sum = margins.entrySet().stream().map(entry -> entry.getKey().multiply(BigDecimal.valueOf(entry.getValue())))
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    summary.put("sumUsd", margins.isEmpty() ? null : sum);
    summary.put("meanUsd", margins.isEmpty() ? null : sum.divide(BigDecimal.valueOf(measurableCases), 8, RoundingMode.HALF_UP));
    summary.put("medianUsd", margins.isEmpty() ? null : atRank(margins, (measurableCases-1)/2).add(atRank(margins, measurableCases/2)).divide(BigDecimal.valueOf(2)));
    result.put("contributionMargin", summary);
    return result;
  }
  private static long count(Object value) { return ((Number) value).longValue(); }
  private static BigDecimal decimal(Object value) { return new BigDecimal(value.toString()); }
  private static void increment(Map<String, Long> reasons, String reason, long count) { reasons.merge(reason, count, Long::sum); }
  private static BigDecimal atRank(SortedMap<BigDecimal, Long> distribution, long rank) {
    long accumulated = 0;
    for (var entry : distribution.entrySet()) {
      accumulated += entry.getValue();
      if (rank < accumulated) return entry.getKey();
    }
    throw new IllegalStateException("Invalid distribution rank");
  }
  private static Object rate(long numerator, long denominator) {
    var result = new LinkedHashMap<String, Object>();
    result.put("numerator", numerator); result.put("denominator", denominator);
    result.put("rate", denominator == 0 ? null : BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 8, RoundingMode.HALF_UP));
    return result;
  }
}

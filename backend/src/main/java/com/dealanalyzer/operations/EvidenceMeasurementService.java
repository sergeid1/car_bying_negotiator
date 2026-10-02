package com.dealanalyzer.operations;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Static aggregate projections only: no case graphs or provider payloads leave this boundary. */
@Service
public class EvidenceMeasurementService {
  private static final String CALLS = " from provider_usage where operation in ('MARKET_RETRIEVAL', 'LISTING_RETRIEVAL') and created_at >= :from and created_at < :to";
  private static final String LATENCY = "extract(epoch from (finished_at - created_at)) * 1000";
  private final EntityManager database;
  private final EvidenceBudget budget;

  public EvidenceMeasurementService(EntityManager database, EvidenceBudget budget) {
    this.database = database;
    this.budget = budget;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> snapshot(Instant from, Instant to) {
    if (!from.isBefore(to)) throw new IllegalArgumentException("Window must have from before to");
    var result = new LinkedHashMap<String, Object>();
    result.put("schemaVersion", "evidence-metrics.v1");
    result.put("window", Map.of("fromInclusive", from, "toExclusive", to));
    result.put("budget", budget.view());
    result.put("providers", rows("select operation, provider, status, count(*), count(cost_usd), count(*) - count(cost_usd), sum(cost_usd), avg(cost_usd), count(finished_at), avg(" + LATENCY + "), percentile_cont(0.95) within group (order by " + LATENCY + ")" + CALLS + " group by operation, provider, status order by operation, provider, status", from, to,
        "operation", "provider", "status", "calls", "callsWithKnownCost", "callsWithUnknownCost", "knownCostUsd", "meanKnownCallCostUsd", "latencySamples", "meanLatencyMillis", "p95LatencyMillis"));
    var observed = rows("select count(*), count(distinct case_id), count(cost_usd), count(*) - count(cost_usd), sum(cost_usd), sum(cost_usd) / nullif(count(distinct case_id), 0), count(finished_at), percentile_cont(0.95) within group (order by " + LATENCY + "), (select max(case_cost) from (select sum(cost_usd) as case_cost" + CALLS + " group by case_id) per_case)" + CALLS, from, to,
        "calls", "casesWithCalls", "callsWithKnownCost", "callsWithUnknownCost", "knownCostUsd", "meanKnownCostPerCaseUsd", "latencySamples", "p95LatencyMillis", "maximumKnownCostPerCaseUsd").get(0);
    long calls = ((Number) observed.get("calls")).longValue();
    boolean allCosts = calls > 0 && ((Number) observed.get("callsWithUnknownCost")).longValue() == 0;
    boolean allLatencies = calls > 0 && ((Number) observed.get("latencySamples")).longValue() == calls;
    observed.put("observedCostPerCaseUsd", allCosts ? observed.get("meanKnownCostPerCaseUsd") : null);
    observed.put("maximumObservedCostPerCaseUsd", allCosts ? observed.get("maximumKnownCostPerCaseUsd") : null);
    observed.put("costComparison", compare(allCosts, observed.get("maximumObservedCostPerCaseUsd"), budget.view().perCaseCostUsd()));
    observed.put("latencyComparison", compare(allLatencies, observed.get("p95LatencyMillis"), budget.view().p95LatencyMillis()));
    result.put("observed", observed);
    result.put("jurisdictions", coverage(from, to));
    return result;
  }

  private String compare(boolean measurable, Object observed, Number ceiling) {
    if (!measurable || observed == null || ceiling == null) return "NOT_MEASURABLE";
    return new BigDecimal(observed.toString()).compareTo(new BigDecimal(ceiling.toString())) > 0 ? "OVER" : "WITHIN";
  }

  private List<Map<String, Object>> coverage(Instant from, Instant to) {
    // Latest assessment and latest report coverage event IN the requested window, one per case.
    // UNION includes unfinished provider attempts and report attempts with no stored assessment.
    String sql = """
        with latest_assessment as (
          select m.id, m.case_id, m.provider_snapshot_id, m.status, m.sample_size from market_assessments m
          where m.created_at >= :from and m.created_at < :to
          and not exists (select 1 from market_assessments n where n.case_id = m.case_id
            and n.created_at >= :from and n.created_at < :to and n.assessment_version > m.assessment_version)
        ), latest_event as (
          select e.id, e.case_id, e.category from pilot_events e where e.event_type = 'MARKET_COVERAGE'
          and e.created_at >= :from and e.created_at < :to
          and not exists (select 1 from pilot_events n where n.case_id = e.case_id
            and n.event_type = 'MARKET_COVERAGE' and n.created_at >= :from and n.created_at < :to
            and (n.created_at > e.created_at or (n.created_at = e.created_at and n.id > e.id)))
        ), attempts as (
          select case_id from latest_assessment union select case_id from latest_event
          union select case_id from provider_usage where operation = 'MARKET_RETRIEVAL'
            and created_at >= :from and created_at < :to
        ), comparable_counts as (
          select provider_snapshot_id, count(*) as evaluated,
            sum(case when decision = 'INCLUDED' then 1 else 0 end) as included,
            sum(case when decision = 'EXCLUDED' then 1 else 0 end) as excluded
          from comparable_snapshots group by provider_snapshot_id
        )
        select j.jurisdiction, count(a.case_id), count(m.id),
          sum(case when m.status = 'AVAILABLE' then 1 else 0 end),
          sum(case when m.status = 'UNAVAILABLE' then 1 else 0 end),
          sum(case when m.status = 'INSUFFICIENT_DATA' then 1 else 0 end),
          count(a.case_id) - count(m.id), count(e.id),
          sum(case when e.category = 'AVAILABLE' then 1 else 0 end),
          sum(case when e.category = 'UNAVAILABLE' then 1 else 0 end),
          coalesce(sum(cc.evaluated), 0), coalesce(sum(cc.included), 0), coalesce(sum(cc.excluded), 0),
          coalesce(sum(m.sample_size), 0)
        from (select distinct jurisdiction from jurisdiction_policies where effective_at < :to) j
        left join analysis_cases c on c.buyer_jurisdiction = j.jurisdiction
        left join attempts a on a.case_id = c.id
        left join latest_assessment m on m.case_id = a.case_id
        left join latest_event e on e.case_id = a.case_id
        left join comparable_counts cc on cc.provider_snapshot_id = m.provider_snapshot_id
        group by j.jurisdiction order by j.jurisdiction
        """;
    return rows(sql, from, to, "jurisdiction", "casesAttempted", "casesWithAssessment",
        "assessmentsAvailable", "assessmentsUnavailable", "assessmentsTooThin", "casesWithoutAssessment",
        "casesWithCoverageEvent", "reportEvidenceAvailable", "reportEvidenceUnavailable",
        "comparablesEvaluated", "comparablesIncluded", "comparablesExcluded", "assessmentSampleCount");
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> rows(String sql, Instant from, Instant to, String... keys) {
    List<Object[]> values = database.createNativeQuery(sql).setParameter("from", from)
        .setParameter("to", to).getResultList();
    return values.stream().map(row -> {
      Map<String, Object> value = new LinkedHashMap<>();
      for (int index = 0; index < keys.length; index++) value.put(keys[index], row[index]);
      return value;
    }).toList();
  }
}

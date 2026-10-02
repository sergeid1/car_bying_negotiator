package com.dealanalyzer.pilot;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Static scalar SQL only. Query definitions and denominators are documented in docs/pilot-metrics-policy.md. */
@Repository
@Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class PilotMetrics {
  private final EntityManager manager;
  private final PilotWorkflowEventRepository events;
  public PilotMetrics(EntityManager manager, PilotWorkflowEventRepository events) { this.manager = manager; this.events = events; }
  private static final String COHORT = " from experiment_assignments a where a.qualified = true";
  private static final String COMPLETED = " exists (select 1 from pilot_events e where e.case_id = a.case_id and e.event_type = 'REPORT_COMPLETED' and e.category = 'COMPLETE')";
  private static final String FINAL = " from outcome_feedback o join experiment_assignments a on a.case_id = o.case_id where a.qualified = true and o.stage = 'FINAL'";

  public Map<String, Object> snapshot() {
    var result = new LinkedHashMap<String, Object>();
    result.put("schemaVersion", "pilot-metrics.v1"); result.put("asOf", Instant.now());
    result.put("scope", "Completion uses all enrolled cases, including anonymous completion counts retained on deletion. Feedback uses retained respondents; workflow totals cover retained service events and deletion receipts. No release decision is automatic.");
    long qualified = count("select enrolled_count from pilot_cohort_totals where id = 1");
    result.put("cohort", rows("select enrolled_count, removed_count from pilot_cohort_totals where id = 1", "enrolled", "deleted"));
    result.put("qualifiedCompletion", rate(count("select count(*)" + COHORT + " and" + COMPLETED) + count("select removed_completed_count from pilot_cohort_totals where id = 1"), qualified));
    result.put("understoodDecisionWithoutUnresolvedDiscrepancy", rate(count("select count(*)" + COHORT + " and" + COMPLETED
        + " and exists (select 1 from analysis_cases c where c.id = a.case_id and c.status = 'COMPLETE' and c.core_review_blocked = false)"
        + " and exists (select 1 from outcome_feedback o where o.case_id = a.case_id and o.stage = 'FINAL' and o.understood_decision = true)"
        + " and exists (select 1 from pilot_events e where e.case_id = a.case_id and e.event_type = 'REVIEW_STATUS' and e.category = 'MATCHES_APPROVED_BASELINE'"
        + " and not exists (select 1 from pilot_events newer where newer.case_id = e.case_id and newer.event_type = 'REVIEW_STATUS' and newer.created_at > e.created_at))") + count("select removed_understood_count from pilot_cohort_totals where id = 1"), qualified));
    long uploaded = count("select count(*)" + COHORT + " and exists (select 1 from source_documents d where d.case_id = a.case_id)");
    long verified = count("select count(*)" + COHORT + " and exists (select 1 from dealer_offers o where o.case_id = a.case_id)");
    result.put("uploadToVerifiedExtraction", rate(verified, uploaded));
    result.put("verifiedExtractionToReport", rate(count("select count(*)" + COHORT + " and exists (select 1 from dealer_offers o where o.case_id = a.case_id) and" + COMPLETED), verified));
    result.put("usefulness", rate(count("select count(*)" + FINAL + " and o.useful = 'YES'"), count("select count(*)" + FINAL + " and o.useful is not null")));
    result.put("trust", rate(count("select count(*)" + FINAL + " and o.trust = 'YES'"), count("select count(*)" + FINAL + " and o.trust is not null")));
    result.put("veryDisappointed", rate(count("select count(*)" + FINAL + " and o.live_use = true and o.disappointment = 'VERY_DISAPPOINTED'"),
        count("select count(*)" + FINAL + " and o.live_use = true and o.disappointment is not null")));
    result.put("findingRatings", rows("select f.accurate, f.understandable, f.useful, count(*) from finding_feedback f join experiment_assignments a on a.case_id = f.case_id group by f.accurate, f.understandable, f.useful", "accurate", "understandable", "useful", "responses"));
    result.put("actionTaken", rows("select o.action, count(*)" + FINAL + " and o.action is not null group by o.action", "action", "responses"));
    result.put("willingnessToPay", rows("select a.variant, a.price_minor, o.willing_to_pay, count(*)" + FINAL + " and o.willing_to_pay is not null group by a.variant, a.price_minor, o.willing_to_pay", "variant", "priceMinor", "willing", "responses"));
    result.put("revisedOfferRate", rate(count("select count(*)" + COHORT + " and exists (select 1 from dealer_offers o where o.case_id = a.case_id and o.offer_version > 1)"), qualified));
    result.put("humanReviewRate", rate(count("select count(*)" + COHORT + " and exists (select 1 from human_review_requests h where h.case_id = a.case_id)"), qualified));
    result.put("correctionRate", rate(count("select count(*)" + COHORT + " and exists (select 1 from deal_term_corrections c where c.case_id = a.case_id)"),
        count("select count(*)" + COHORT + " and exists (select 1 from document_extractions e where e.case_id = a.case_id)")));
    result.put("marketCoverage", rate(count("select count(*)" + COHORT + " and exists (select 1 from pilot_events e where e.case_id = a.case_id and e.event_type = 'MARKET_COVERAGE' and e.category = 'AVAILABLE' and not exists (select 1 from pilot_events n where n.case_id = e.case_id and n.event_type = 'MARKET_COVERAGE' and n.created_at > e.created_at))"), count("select count(*)" + COHORT + " and exists (select 1 from pilot_events e where e.case_id = a.case_id and e.event_type = 'REPORT_COMPLETED')")));
    result.put("marketConfidence", rows("select m.confidence, m.status, count(*) from market_assessments m join experiment_assignments a on a.case_id = m.case_id where not exists (select 1 from market_assessments n where n.case_id = m.case_id and n.assessment_version > m.assessment_version) group by m.confidence, m.status", "confidence", "status", "assessments"));
    result.put("reportTotalIdentification", rows("select count(*), count(duration_millis), avg(duration_millis), percentile_cont(0.95) within group (order by duration_millis) from pilot_events where event_type = 'REPORT_TOTAL_IDENTIFICATION'", "opens", "correctIdentifications", "meanMillis", "p95Millis"));
    result.put("workflowEvents", events.aggregate());
    result.put("latency", rows("select event_type, count(duration_millis), avg(duration_millis), percentile_cont(0.95) within group (order by duration_millis) from pilot_workflow_events where duration_millis is not null group by event_type", "eventType", "samples", "meanMillis", "p95Millis"));
    result.put("providerCost", rows("select u.status, count(*), count(u.cost_usd), sum(u.cost_usd) from provider_usage u group by u.status", "status", "calls", "callsWithKnownCost", "knownCostUsd"));
    result.put("providerCostPerCompletedCase", cost("COMPLETE"));
    result.put("providerCostPerFailedCase", cost("FAILED"));
    result.put("deletion", rows("select state, count(*), avg(extract(epoch from (completed_at - requested_at)) * 1000), percentile_cont(0.95) within group (order by extract(epoch from (completed_at - requested_at)) * 1000) from case_deletion_jobs group by state", "state", "cases", "meanMillis", "p95Millis"));
    result.put("completedDeletionWithin24Hours", rate(count("select count(*) from case_deletion_jobs where state = 'DELETED' and extract(epoch from (completed_at - requested_at)) <= 86400"), count("select count(*) from case_deletion_jobs where state = 'DELETED'")));
    result.put("overduePendingDeletions", ((Number) manager.createNativeQuery("select count(*) from case_deletion_jobs where state <> 'DELETED' and requested_at < :cutoff")
        .setParameter("cutoff", Instant.now().minusSeconds(86400)).getSingleResult()).longValue());
    var measured = new LinkedHashMap<String, Object>();
    for (var metric : PilotService.Metric.values()) {
      var row = (Object[]) manager.createNativeQuery("select coalesce(sum(numerator), 0), coalesce(sum(denominator), 0) from pilot_observations where metric = :metric")
          .setParameter("metric", metric.name()).getSingleResult();
      measured.put(metric.name(), rate(((Number) row[0]).longValue(), ((Number) row[1]).longValue()));
    }
    result.put("verifiedMeasurements", measured);
    result.put("acquisitionChannels", channels());
    result.put("verifiedOutcomeDelta", rows("select count(total_delta_minor), sum(total_delta_minor), sum(case when total_delta_minor is not null then denominator else 0 end) from pilot_observations where metric = 'VERIFIED_OUTCOME_IMPROVEMENT'", "batches", "totalDeltaMinorUsd", "verifiedCases"));
    return result;
  }
  private static final String SUPPORTED = " exists (select 1 from jurisdiction_policies p where p.jurisdiction = c.buyer_jurisdiction)";

  /** Qualified uploads per first-party channel and for no channel; the channel key "" is no channel. */
  public Map<String, Object> channels() {
    var keys = new ArrayList<String>(); keys.add("");
    for (Object code : manager.createNativeQuery("select code from acquisition_channels order by code").getResultList()) keys.add((String) code);
    var qualified = grouped("select coalesce(c.acquisition_channel, ''), count(*), sum(case when exists (select 1 from source_documents d where d.case_id = a.case_id) then 1 else 0 end)"
        + " from experiment_assignments a join analysis_cases c on c.id = a.case_id where a.qualified = true and" + SUPPORTED + " group by coalesce(c.acquisition_channel, '')");
    var starts = grouped("select coalesce(c.acquisition_channel, ''), count(*), 0 from analysis_cases c where" + SUPPORTED + " group by coalesce(c.acquisition_channel, '')");
    var removed = new HashMap<String, long[]>();
    for (var row : rowsOf("select channel_key, removed_case_starts, removed_qualified_count, removed_qualified_uploads from acquisition_channel_totals"))
      removed.put((String) row[0], new long[] {number(row[1]), number(row[2]), number(row[3])});
    var spend = new HashMap<String, Object[]>();
    for (var row : rowsOf("select channel_code, count(*), sum(amount_minor), min(period_start), max(period_end) from acquisition_channel_spend group by channel_code"))
      spend.put((String) row[0], row);
    long allUploads = 0;
    for (var key : keys) allUploads += qualified.getOrDefault(key, new long[2])[1] + removed.getOrDefault(key, new long[3])[2];
    var perChannel = new ArrayList<Map<String, Object>>();
    for (var key : keys) {
      long[] retained = qualified.getOrDefault(key, new long[2]), deleted = removed.getOrDefault(key, new long[3]);
      long cases = retained[0] + deleted[1], uploads = retained[1] + deleted[2];
      var row = new LinkedHashMap<String, Object>();
      row.put("channel", key.isEmpty() ? null : key);
      row.put("qualifiedUploads", uploads);
      row.put("qualifiedUploadRate", rate(uploads, cases));
      row.put("shareOfQualifiedUploads", rate(uploads, allUploads));
      row.put("caseStarts", starts.getOrDefault(key, new long[2])[0] + deleted[0]);
      var paid = spend.get(key);
      long spent = paid == null ? 0 : number(paid[2]);
      var spendView = new LinkedHashMap<String, Object>();
      spendView.put("records", paid == null ? 0 : number(paid[1])); spendView.put("totalMinor", spent); spendView.put("currency", "USD");
      spendView.put("firstPeriodStart", paid == null ? null : String.valueOf(paid[3]));
      spendView.put("lastPeriodEnd", paid == null ? null : String.valueOf(paid[4]));
      row.put("spend", spendView);
      var cost = new LinkedHashMap<String, Object>();
      cost.put("spendMinor", spent); cost.put("qualifiedUploads", uploads);
      boolean measurable = paid != null && uploads > 0;
      cost.put("costMinor", measurable ? (double) spent / uploads : null);
      cost.put("status", measurable ? "MEASURED" : "UNAVAILABLE");
      if (!measurable) cost.put("reason", paid == null ? "NO_SPEND_RECORDED" : "NO_QUALIFIED_UPLOADS");
      row.put("costPerQualifiedUpload", cost);
      // No purchase record exists yet, so no paid case can be attributed to a channel.
      row.put("costPerPaidCase", Map.of("status", "UNAVAILABLE", "reason", "NO_PURCHASE_RECORD"));
      perChannel.add(row);
    }
    var result = new LinkedHashMap<String, Object>();
    result.put("schemaVersion", "acquisition-channels.v1");
    result.put("primaryMeasure", "qualifiedUploads");
    result.put("scope", "Qualified uploads are enrolled qualified cases in a supported jurisdiction with at least one accepted upload, including anonymous counts retained on deletion. Case starts are secondary context. Spend is cumulative over all recorded periods.");
    result.put("channels", perChannel);
    return result;
  }
  private Map<String, long[]> grouped(String sql) {
    var result = new HashMap<String, long[]>();
    for (var row : rowsOf(sql)) result.put((String) row[0], new long[] {number(row[1]), number(row[2])});
    return result;
  }
  @SuppressWarnings("unchecked") private List<Object[]> rowsOf(String sql) { return manager.createNativeQuery(sql).getResultList(); }
  private static long number(Object value) { return value == null ? 0 : ((Number) value).longValue(); }

  public Map<String, Object> research() {
    // Current consent and active retention are checked on every query; no secondary materialized dataset exists.
    String eligible = " from outcome_feedback o join analysis_cases c on c.id = o.case_id where o.stage = 'FINAL' and c.lifecycle = 'ACTIVE' and c.access_expires_at > :now"
        + " and exists (select 1 from consent_records r where r.case_id = o.case_id and r.purpose = 'ANONYMIZED_RESEARCH' and r.action = 'GRANTED'"
        + " and r.consent_version = (select max(r2.consent_version) from consent_records r2 where r2.case_id = r.case_id and r2.purpose = r.purpose))"
        + " and exists (select 1 from consent_records s where s.case_id = o.case_id and s.purpose = 'SERVICE_PROCESSING' and s.action = 'GRANTED'"
        + " and s.consent_version = (select max(s2.consent_version) from consent_records s2 where s2.case_id = s.case_id and s2.purpose = s.purpose))";
    long respondents = ((Number) manager.createNativeQuery("select count(*)" + eligible).setParameter("now", Instant.now()).getSingleResult()).longValue();
    long useful = ((Number) manager.createNativeQuery("select count(*)" + eligible + " and o.useful = 'YES'").setParameter("now", Instant.now()).getSingleResult()).longValue();
    return Map.of("respondents", respondents, "usefulResponses", useful, "scope", "Current separate research consent; no dealer intelligence or case-level export.");
  }
  private Object cost(String status) {
    // One scalar row per case in the derived table avoids repeated-case denominators from provider joins.
    return rows("select count(*), sum(t.calls), sum(t.known_calls), sum(t.known_cost), avg(t.known_cost) from ("
        + "select c.id, count(u.id) as calls, count(u.cost_usd) as known_calls, sum(u.cost_usd) as known_cost "
        + "from analysis_cases c left join provider_usage u on u.case_id = c.id where c.status = '" + status + "' group by c.id) t",
        "cases", "calls", "callsWithKnownCost", "knownCostUsd", "meanKnownCostUsd");
  }
  private long count(String sql) { return ((Number) manager.createNativeQuery(sql).getSingleResult()).longValue(); }
  private Map<String, Object> rate(long numerator, long denominator) {
    var result = new LinkedHashMap<String, Object>(); result.put("numerator", numerator); result.put("denominator", denominator);
    result.put("rate", denominator == 0 ? null : (double) numerator / denominator);
    result.put("status", denominator == 0 ? "UNAVAILABLE" : "MEASURED"); return result;
  }
  private List<Map<String, Object>> rows(String sql, String... columns) {
    @SuppressWarnings("unchecked") List<Object[]> values = manager.createNativeQuery(sql).getResultList();
    return values.stream().map(valuesRow -> {
      Map<String, Object> row = new LinkedHashMap<>();
      for (int i = 0; i < columns.length; i++) row.put(columns[i], valuesRow[i]); return row;
    }).toList();
  }
}

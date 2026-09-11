package com.dealanalyzer.buyingcase.persistence;

import jakarta.persistence.EntityManager;
import java.util.List;
import org.springframework.stereotype.Repository;

/** Fixed child-before-parent plan. See docs/case-deletion-policy.md before changing the graph. */
@Repository
class CaseGraphDeletionPlan {
  private record Step(String table, String predicate) {}
  private static final List<Step> STEPS = List.of(
      new Step("experiment_assignments", "case_id = :caseId"),
      new Step("outcome_feedback", "case_id = :caseId"),
      new Step("pilot_events", "case_id = :caseId"),
      new Step("finding_feedback", "case_id = :caseId"),
      new Step("operator_audit", "case_id = :caseId"),
      new Step("human_review_requests", "case_id = :caseId"),
      new Step("provider_usage", "case_id = :caseId"),
      new Step("case_recovery_tokens", "case_id = :caseId"),
      new Step("case_delivery_requests", "case_id = :caseId"),
      new Step("case_recovery_contacts", "case_id = :caseId"),
      new Step("case_export_jobs", "case_id = :caseId"),
      new Step("negotiation_versions", "case_id = :caseId"),
      new Step("deal_reviews", "case_id = :caseId"),
      new Step("financing_comparisons", "case_id = :caseId"),
      new Step("outside_finance_offers", "case_id = :caseId"),
      new Step("financing_results", "case_id = :caseId"),
      new Step("finance_offers", "case_id = :caseId"),
      new Step("price_plan_snapshots", "case_id = :caseId"),
      new Step("market_assessments", "case_id = :caseId"),
      new Step("comparable_snapshots", "provider_snapshot_id in (select id from market_provider_snapshots where case_id = :caseId)"),
      new Step("market_provider_snapshots", "case_id = :caseId"),
      new Step("cash_economics_results", "case_id = :caseId"),
      new Step("audit_events", "case_id = :caseId"),
      new Step("deal_term_verifications", "case_id = :caseId"),
      new Step("dealer_offers", "case_id = :caseId"),
      new Step("deal_term_corrections", "case_id = :caseId"),
      new Step("extraction_validation_issues", "extraction_id in (select id from document_extractions where case_id = :caseId)"),
      new Step("quote_line_items", "extraction_id in (select id from document_extractions where case_id = :caseId)"),
      new Step("candidate_deal_terms", "extraction_id in (select id from document_extractions where case_id = :caseId)"),
      new Step("document_extractions", "case_id = :caseId"),
      new Step("document_operation_retries", "operation_id in (select id from document_processing_operations where case_id = :caseId)"),
      new Step("document_processing_operations", "case_id = :caseId"),
      new Step("document_pages", "document_id in (select id from source_documents where case_id = :caseId)"),
      new Step("source_documents", "case_id = :caseId"),
      new Step("vehicle_identity_conflicts", "case_id = :caseId"),
      new Step("listing_snapshots", "case_id = :caseId"),
      new Step("dealers", "case_id = :caseId"),
      new Step("vehicles", "case_id = :caseId"),
      new Step("consent_records", "case_id = :caseId"),
      new Step("analysis_reports", "case_id = :caseId"),
      new Step("operations", "case_id = :caseId"),
      new Step("analysis_cases", "id = :caseId"));
  private final EntityManager manager;
  CaseGraphDeletionPlan(EntityManager manager) { this.manager = manager; }

  long deleteAndVerify(String caseId) {
    manager.flush();
    manager.createNativeQuery("update dealer_offers set previous_offer_id = null where case_id = :caseId")
        .setParameter("caseId", caseId).executeUpdate();
    // Preserve only anonymous cohort counters before erasing every case-linked pilot record.
    manager.createNativeQuery("""
        update pilot_cohort_totals set
          removed_count = removed_count + (select count(*) from experiment_assignments where case_id = :caseId),
          removed_completed_count = removed_completed_count + (select count(*) from experiment_assignments a where a.case_id = :caseId
            and exists (select 1 from pilot_events e where e.case_id = a.case_id and e.event_type = 'REPORT_COMPLETED' and e.category = 'COMPLETE')),
          removed_understood_count = removed_understood_count + (select count(*) from experiment_assignments a where a.case_id = :caseId
            and exists (select 1 from analysis_cases c where c.id = a.case_id and c.status = 'COMPLETE' and c.core_review_blocked = false)
            and exists (select 1 from outcome_feedback o where o.case_id = a.case_id and o.stage = 'FINAL' and o.understood_decision = true)
            and exists (select 1 from pilot_events e where e.case_id = a.case_id and e.event_type = 'REVIEW_STATUS' and e.category = 'MATCHES_APPROVED_BASELINE'
              and not exists (select 1 from pilot_events n where n.case_id = e.case_id and n.event_type = 'REVIEW_STATUS' and n.created_at > e.created_at)))
        where id = 1
        """).setParameter("caseId", caseId).executeUpdate();
    long removed = 0;
    for (var step : STEPS) {
      removed += manager.createNativeQuery("delete from " + step.table + " where " + step.predicate)
          .setParameter("caseId", caseId).executeUpdate();
      long remaining = ((Number) manager.createNativeQuery("select count(*) from " + step.table + " where " + step.predicate)
          .setParameter("caseId", caseId).getSingleResult()).longValue();
      if (remaining != 0) throw new IllegalStateException("Deletion verification failed");
    }
    return removed;
  }

  void deleteExpiredExports(java.time.Instant now) {
    manager.createNativeQuery("delete from case_export_jobs where expires_at <= :now")
        .setParameter("now", now).executeUpdate();
  }
}

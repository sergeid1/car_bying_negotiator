package com.dealanalyzer.buyingcase.persistence;

import jakarta.persistence.EntityManager;
import java.util.List;
import org.springframework.stereotype.Repository;

/** Fixed child-before-parent plan. See docs/case-deletion-policy.md before changing the graph. */
@Repository
class CaseGraphDeletionPlan {
  private record Step(String table, String predicate) {}
  private static final List<Step> STEPS = List.of(
      new Step("dealer_relay_attachments", "case_id = :caseId"),
      new Step("dealer_relay_send_attempts", "case_id = :caseId"),
      new Step("dealer_relay_messages", "case_id = :caseId"),
      new Step("dealer_relay_identities", "case_id = :caseId"),
      new Step("buyer_account_cases", "case_id = :caseId"),
      new Step("case_support_time", "case_id = :caseId"),
      new Step("case_purchase_requests", "case_id = :caseId"),
      new Step("case_purchases", "case_id = :caseId"),
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
      new Step("report_explanations", "case_id = :caseId"),
      new Step("negotiation_versions", "case_id = :caseId"),
      new Step("deal_reviews", "case_id = :caseId"),
      new Step("financing_comparisons", "case_id = :caseId"),
      new Step("outside_finance_offers", "case_id = :caseId"),
      new Step("financing_results", "case_id = :caseId"),
      new Step("finance_offers", "case_id = :caseId"),
      new Step("negotiation_mandate_revocations", "case_id = :caseId"),
      new Step("negotiation_mandates", "case_id = :caseId"),
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
    com.dealanalyzer.purchase.ContributionService.retainAnonymousTotals(manager, caseId);
    manager.createNativeQuery("update dealer_offers set previous_offer_id = null where case_id = :caseId")
        .setParameter("caseId", caseId).executeUpdate();
    // Preserve only anonymous cohort counters before erasing every case-linked pilot record.
    manager.createNativeQuery("""
        update purchase_totals set
          removed_case_count = removed_case_count + (select count(*) from analysis_cases where id = :caseId),
          removed_paid_count = removed_paid_count + (select count(*) from case_purchases where case_id = :caseId and status = 'PAID'),
          removed_refund_count = removed_refund_count + (select count(*) from case_purchases where case_id = :caseId and refunded_at is not null)
        where id = 1
        """).setParameter("caseId", caseId).executeUpdate();
    manager.createNativeQuery("""
        update acquisition_channel_totals set removed_paid_count = removed_paid_count +
          (select count(*) from case_purchases p join analysis_cases c on c.id = p.case_id
            where p.case_id = :caseId and p.status = 'PAID'
              and exists (select 1 from jurisdiction_policies j where j.jurisdiction = c.buyer_jurisdiction))
        where channel_key = (select coalesce(acquisition_channel, '') from analysis_cases where id = :caseId)
        """).setParameter("caseId", caseId).executeUpdate();
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
    // Same rule for first-party channel counts: anonymous per-channel totals, no case reference.
    manager.createNativeQuery("""
        update acquisition_channel_totals set
          removed_case_starts = removed_case_starts + (select count(*) from analysis_cases c where c.id = :caseId
            and exists (select 1 from jurisdiction_policies p where p.jurisdiction = c.buyer_jurisdiction)),
          removed_qualified_count = removed_qualified_count + (select count(*) from experiment_assignments a join analysis_cases c on c.id = a.case_id
            where a.case_id = :caseId and a.qualified = true and exists (select 1 from jurisdiction_policies p where p.jurisdiction = c.buyer_jurisdiction)),
          removed_qualified_uploads = removed_qualified_uploads + (select count(*) from experiment_assignments a join analysis_cases c on c.id = a.case_id
            where a.case_id = :caseId and a.qualified = true and exists (select 1 from jurisdiction_policies p where p.jurisdiction = c.buyer_jurisdiction)
            and exists (select 1 from source_documents d where d.case_id = a.case_id))
        where channel_key = (select coalesce(c.acquisition_channel, '') from analysis_cases c where c.id = :caseId)
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

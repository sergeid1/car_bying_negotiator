package com.dealanalyzer.buyingcase.persistence;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.util.*;
import org.springframework.stereotype.Repository;
/** Explicit scalar projections keep credentials and storage keys out of export assembly. */
@Repository
class CaseExportProjection {
  private final EntityManager manager;
  private final ObjectMapper json;
  CaseExportProjection(EntityManager manager, ObjectMapper json) { this.manager=manager; this.json=json; }
  ObjectNode read(String caseId) {
    var result=json.createObjectNode();
    result.set("journey_candidate_cases", rows("journey_candidate_cases", "case_id, journey_id, account_id, candidate_id, created_at", "case_id = :caseId", caseId));
    result.set("agreed_prices", rows("agreed_prices", "id, case_id, acceptance_request_id, dealer_offer_id, offer_version, baseline_id, baseline_version, actor, accepted_at, evidence_message_id, evidence_document_id, journey_result_json", "case_id = :caseId", caseId));
    result.set("quote_acceptance_requests", rows("quote_acceptance_requests", "id, case_id, quote_request_id, draft_id, offer_version, plan_version, evidence_message_id, evidence_document_id, kind, state, message_id, created_at", "case_id = :caseId", caseId));
    result.set("quote_requests", rows("quote_requests", "id, case_id, draft_id, dealer_address, state, message_id, approved_at, created_at", "case_id = :caseId", caseId));
    result.set("case_negotiation_states", rows("case_negotiation_states", "case_id, mandate_id, state, updated_at", "case_id = :caseId", caseId));
    result.set("analysis_cases", rows("analysis_cases", "id, buyer_zip, dealer_zip, purchase_method, status, vehicle_json, dealer_offer_json, input_json, input_fingerprint, current_report_version, created_at, updated_at, buyer_jurisdiction, dealer_location, max_selling_price_minor, max_out_the_door_price_minor, access_expires_at, core_review_blocked, acquisition_channel", "id = :caseId", caseId));
    result.set("operations", rows("operations", "id, case_id, stage, error_message, created_at, updated_at", "case_id = :caseId", caseId));
    result.set("analysis_reports", rows("analysis_reports", "case_id, report_version, payload_json, analysis_fingerprint, created_at, operation_id", "case_id = :caseId", caseId));
    result.set("experiment_assignments", rows("experiment_assignments", "id, case_id, variant, price_minor, qualified, created_at", "case_id = :caseId", caseId));
    result.set("finding_feedback", rows("finding_feedback", "id, case_id, report_version, finding_pointer, accurate, understandable, useful, created_at", "case_id = :caseId", caseId));
    result.set("outcome_feedback", rows("outcome_feedback", "id, case_id, payload_json, created_at", "case_id = :caseId", caseId));
    result.set("pilot_events", rows("pilot_events", "id, case_id, event_type, category, duration_millis, created_at", "case_id = :caseId", caseId));
    result.set("consent_records", rows("consent_records", "id, case_id, purpose, consent_version, action, policy_version, occurred_at, actor, source, schema_version", "case_id = :caseId", caseId));
    result.set("vehicles", rows("vehicles", "id, case_id, vin, model_year, make, model, trim_name, mileage, confirmation_source, confirmed_at, updated_at", "case_id = :caseId", caseId));
    result.set("dealers", rows("dealers", "id, case_id, source, provider_dealer_id, name, city, state_code, created_at", "case_id = :caseId", caseId));
    result.set("listing_snapshots", rows("listing_snapshots", "id, case_id, dealer_id, provider_listing_id, listing_url, vin, model_year, make, model, trim_name, mileage, advertised_price_minor, currency, source, retrieved_at, provider_version, license_metadata, artifact_reference, created_at", "case_id = :caseId", caseId));
    result.set("vehicle_identity_conflicts", rows("vehicle_identity_conflicts", "id, case_id, listing_snapshot_id, field_name, confirmed_value, listing_value, status, created_at, resolved_at", "case_id = :caseId", caseId));
    result.set("source_documents", rows("source_documents", "id, case_id, original_filename, checksum_sha256, verified_media_type, size_bytes, page_count, lifecycle, retention_deadline, encryption_algorithm, created_at, updated_at, original_deleted_at", "case_id = :caseId", caseId));
    result.set("document_pages", rows("document_pages", "id, document_id, page_number, width_pixels, height_pixels, resolution_dpi, likely_missing, likely_duplicate, likely_rotated, likely_cropped, created_at, normalized_deleted_at", "document_id in (select id from source_documents where case_id = :caseId)", caseId));
    result.set("document_processing_operations", rows("document_processing_operations", "id, case_id, document_id, stage, failed_stage, error_code, error_message, retry_count, created_at, updated_at", "case_id = :caseId", caseId));
    result.set("document_operation_retries", rows("document_operation_retries", "id, operation_id, created_at", "operation_id in (select id from document_processing_operations where case_id = :caseId)", caseId));
    result.set("document_extractions", rows("document_extractions", "id, document_id, case_id, extraction_version, status, extractor_version, model_version, schema_version, overall_confidence, suspected_missing_pages, document_warnings, created_at", "case_id = :caseId", caseId));
    result.set("candidate_deal_terms", rows("candidate_deal_terms", "id, extraction_id, field_key, original_wording, normalized_value, normalized_unit, page_number, region_x, region_y, region_width, region_height, confidence, material, verification_state, created_at", "extraction_id in (select id from document_extractions where case_id = :caseId)", caseId));
    result.set("quote_line_items", rows("quote_line_items", "id, extraction_id, original_label, normalized_category, amount_minor, currency, tax_claim, optionality_claim, page_number, region_x, region_y, region_width, region_height, confidence, verification_state, created_at", "extraction_id in (select id from document_extractions where case_id = :caseId)", caseId));
    result.set("extraction_validation_issues", rows("extraction_validation_issues", "id, extraction_id, severity, issue_code, field_key, message, created_at", "extraction_id in (select id from document_extractions where case_id = :caseId)", caseId));
    result.set("deal_term_corrections", rows("deal_term_corrections", "id, case_id, extraction_id, target_type, target_id, correction_version, normalized_value, normalized_unit, normalized_category, amount_minor, actor, created_at", "case_id = :caseId", caseId));
    result.set("dealer_offers", rows("dealer_offers", "id, case_id, extraction_id, offer_version, status, schema_version, terms_json, created_at, previous_offer_id", "case_id = :caseId", caseId));
    result.set("deal_term_verifications", rows("deal_term_verifications", "id, case_id, extraction_id, dealer_offer_id, target_type, target_id, verification_version, effective_value, actor, created_at", "case_id = :caseId", caseId));
    result.set("audit_events", rows("audit_events", "id, case_id, event_type, aggregate_type, aggregate_id, aggregate_version, actor, metadata_json, created_at", "case_id = :caseId", caseId));
    result.set("cash_economics_results", rows("cash_economics_results", "id, case_id, dealer_offer_id, policy_version, eligibility_fingerprint, schema_version, result_json, created_at", "case_id = :caseId", caseId));
    result.set("market_provider_snapshots", rows("market_provider_snapshots", "id, case_id, source, provider_version, schema_version, license_metadata, artifact_reference, availability, unavailable_reason, retrieved_at, created_at", "case_id = :caseId", caseId));
    result.set("comparable_snapshots", rows("comparable_snapshots", "id, provider_snapshot_id, provider_observation_id, listing_url, model_year, make, model, trim_name, mileage, price_minor, currency, geography, observed_at, decision, reason_code, reason, match_factors_json, adjustment_minor, adjustment_precision, adjustment_explanation, adjusted_price_minor, ordinal", "provider_snapshot_id in (select id from market_provider_snapshots where case_id = :caseId)", caseId));
    result.set("market_assessments", rows("market_assessments", "id, case_id, provider_snapshot_id, schema_version, assessment_version, status, range_low_minor, range_high_minor, currency, geography, oldest_observation_at, newest_observation_at, sample_size, method_version, confidence, limitation, created_at", "case_id = :caseId", caseId));
    result.set("price_plan_snapshots", rows("price_plan_snapshots", "id, case_id, kind, version, payload_json, created_at", "case_id = :caseId", caseId));
    result.set("negotiation_mandates", rows("negotiation_mandates", "id, case_id, version, plan_snapshot_id, plan_version, currency, target_minor, ceiling_minor, plan_target_minor, plan_ceiling_minor, disclosures, autonomy_mode, expires_at, approved_by, approved_at", "case_id = :caseId", caseId));
    result.set("negotiation_mandate_revocations", rows("negotiation_mandate_revocations", "mandate_id, case_id, revoked_by, revoked_at", "case_id = :caseId", caseId));
    result.set("finance_offers", rows("finance_offers", "id, case_id, version, dealer_offer_id, payload_json, created_at", "case_id = :caseId", caseId));
    result.set("financing_results", rows("financing_results", "id, case_id, version, finance_offer_id, policy_version, payload_json, created_at", "case_id = :caseId", caseId));
    result.set("outside_finance_offers", rows("outside_finance_offers", "id, case_id, version, dealer_finance_offer_id, payload_json, created_at", "case_id = :caseId", caseId));
    result.set("financing_comparisons", rows("financing_comparisons", "id, case_id, version, outside_finance_offer_id, policy_version, payload_json, created_at", "case_id = :caseId", caseId));
    result.set("deal_reviews", rows("deal_reviews", "id, case_id, version, baseline_id, previous_offer_id, current_offer_id, tolerance_policy, payload_json, created_at", "case_id = :caseId", caseId));
    result.set("negotiation_versions", rows("negotiation_versions", "case_id, report_version, payload_json", "case_id = :caseId", caseId));
    result.set("outbound_drafts", rows("outbound_drafts", "id, case_id, dealer_id, mandate_id, draft_text, gate_version, decision_json, context_json, created_at", "case_id = :caseId", caseId));
    result.set("report_explanations", rows("report_explanations", "case_id, report_version, policy_version, path, fallback_reason, model_provider, model_id, prompt_version, schema_version, threshold_set_version, evaluation_set_version, payload_json, created_at", "case_id = :caseId", caseId));
    result.set("dealer_relay_identities", rows("dealer_relay_identities", "id, case_id, address, created_at, dealer_opted_out_at, opt_out_channel", "case_id = :caseId", caseId));
    result.set("dealer_relay_messages", rows("dealer_relay_messages", "id, case_id, identity_id, direction, trust, dealer_address, state, size_bytes, provider_message_id, created_at, completed_at, body_erased_at", "case_id = :caseId", caseId));
    result.set("dealer_relay_attachments", rows("dealer_relay_attachments", "id, message_id, case_id, ordinal, document_id, outcome, created_at", "case_id = :caseId", caseId));
    result.set("dealer_relay_send_attempts", rows("dealer_relay_send_attempts", "id, case_id, message_id, outcome, created_at", "case_id = :caseId", caseId));
    result.set("recovery_contact", rows("case_recovery_contacts", "case_id, email, expires_at", "case_id = :caseId", caseId));
    result.set("delivery_requests", rows("case_delivery_requests", "id, case_id, email, expires_at", "case_id = :caseId", caseId));
    return result;
  }
  private com.fasterxml.jackson.databind.JsonNode rows(String table, String columns, String where, String caseId) {
    String[] names=columns.split(", ");
    var result=json.createArrayNode();
    var rows=manager.createNativeQuery("select " + columns + " from " + table + " where " + where + " order by 1")
        .setParameter("caseId", caseId).getResultList();
    for (Object item : rows) {
      Object[] row=item instanceof Object[] array ? array : new Object[]{item};
      var record=result.addObject();
      for (int i=0;i<names.length;i++) {
        Object value=row[i];
        if (value==null) record.putNull(names[i]);
        else if (names[i].endsWith("_json")) {
          try { record.set(names[i], json.readTree(value.toString())); }
          catch (java.io.IOException failure) { throw new IllegalStateException("Stored export data is unreadable", failure); }
        } else if (value instanceof Number || value instanceof Boolean) record.set(names[i], json.valueToTree(value));
        else record.put(names[i], value.toString());
      }
    }
    return result;
  }
}

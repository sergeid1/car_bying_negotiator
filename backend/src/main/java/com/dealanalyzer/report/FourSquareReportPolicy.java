package com.dealanalyzer.report;

import com.dealanalyzer.extraction.persistence.DocumentExtractionPersistence.ExtractionView;
import java.util.List;

/** Documentary findings only: candidates never establish verified totals or loan terms. */
public final class FourSquareReportPolicy {
  public List<String> topReasons(List<String> worksheetFindings, List<String> existingReasons) {
    if (worksheetFindings.isEmpty()) return List.copyOf(existingReasons);
    String worksheetReason = worksheetFindings.size() > 1
        ? "The four-square worksheet shows a monthly payment but the out-the-door total and loan term are missing; payment-led figures hide the total, so ask for an itemized total and the term in months and do not infer either from payment."
        : worksheetFindings.getFirst();
    var reasons = new java.util.ArrayList<String>();
    reasons.add(worksheetReason);
    reasons.addAll(existingReasons.stream().limit(2).toList());
    return List.copyOf(reasons);
  }

  public List<String> findings(ExtractionView extraction) {
    if (extraction == null || extraction.candidates().stream().noneMatch(candidate ->
        candidate.field().equals("DOCUMENT_TYPE")
            && "FOUR_SQUARE_WORKSHEET".equals(candidate.normalizedValue()))) return List.of();
    return extraction.validationIssues().stream()
        .filter(issue -> issue.code().startsWith("PAYMENT_LED_MISSING_"))
        .sorted(java.util.Comparator.comparing(issue -> issue.code().equals("PAYMENT_LED_MISSING_TOTAL") ? 0 : 1))
        .map(issue -> issue.message()).toList();
  }
}

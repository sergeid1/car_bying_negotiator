package com.dealanalyzer.pilot;

import com.dealanalyzer.buyingcase.ResourceNotFoundException;
import com.dealanalyzer.buyingcase.persistence.BuyingCaseStore;
import com.dealanalyzer.report.ReportLead;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportTotalService {
  private final FeedbackService feedback;
  private final BuyingCaseStore reports;
  private final PilotEventRepository events;
  public ReportTotalService(FeedbackService feedback, BuyingCaseStore reports, PilotEventRepository events) {
    this.feedback = feedback; this.reports = reports; this.events = events;
  }
  public record Open(@Min(1) int reportVersion) {}
  public record Answer(@Min(0) Long answerMinor, boolean unknown, @Min(0) @Max(86400000) long durationMillis) {}
  @Transactional
  public Object open(String caseId, String token, Open input) {
    feedback.lock(caseId, token);
    reports.requireReport(caseId, java.util.Optional.of(input.reportVersion()));
    var row = new PilotEventEntity(); row.id = UUID.randomUUID().toString(); row.caseId = caseId;
    row.reportVersion = input.reportVersion(); row.eventType = "REPORT_TOTAL_IDENTIFICATION"; row.category = "OPENED";
    row.createdAt = Instant.now(); events.saveAndFlush(row);
    return Map.of("id", row.id);
  }
  @Transactional
  public Object identify(String caseId, String token, String id, Answer input) {
    feedback.lock(caseId, token);
    var row = events.findById(id).filter(event -> event.caseId.equals(caseId)
        && event.eventType.equals("REPORT_TOTAL_IDENTIFICATION")).orElseThrow(() -> new ResourceNotFoundException("Measure not found"));
    if (row.category.equals("CORRECT")) return Map.of("correct", true);
    var total = ReportLead.total(reports.requireReport(caseId, java.util.Optional.of(row.reportVersion)));
    boolean correct = ReportLead.known(total)
        ? !input.unknown() && input.answerMinor() != null && total.path("currency").asText().equals("USD")
            && total.path("minorUnits").asLong() == input.answerMinor()
        : input.unknown() && input.answerMinor() == null;
    if (correct) { row.category = "CORRECT"; row.durationMillis = input.durationMillis(); events.saveAndFlush(row); }
    return Map.of("correct", correct);
  }
}

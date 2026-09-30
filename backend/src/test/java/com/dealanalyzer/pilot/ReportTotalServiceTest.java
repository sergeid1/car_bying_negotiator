package com.dealanalyzer.pilot;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.dealanalyzer.buyingcase.persistence.BuyingCaseStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReportTotalServiceTest {
  @Test void comparesExactCentsAgainstSessionVersionAndKeepsFirstSuccessfulTime() throws Exception {
    var feedback = mock(FeedbackService.class);
    var store = mock(BuyingCaseStore.class);
    var events = mock(PilotEventRepository.class);
    var row = new PilotEventEntity(); row.id = "session"; row.caseId = "case"; row.reportVersion = 2;
    row.eventType = "REPORT_TOTAL_IDENTIFICATION"; row.category = "OPENED";
    when(events.findById("session")).thenReturn(Optional.of(row));
    when(store.requireReport("case", Optional.of(2))).thenReturn(new ObjectMapper().readTree("""
        {"analysis":{"materialAmounts":[{"label":"Out-the-door total","minorUnits":2850012,"currency":"USD","precision":"ESTIMATED"}]}}
        """));
    var service = new ReportTotalService(feedback, store, events);
    assertThat(service.identify("case", "token", "session", new ReportTotalService.Answer(2850000L, false, 1000))).isEqualTo(java.util.Map.of("correct", false));
    assertThat(row.durationMillis).isNull();
    assertThat(service.identify("case", "token", "session", new ReportTotalService.Answer(2850012L, false, 2000))).isEqualTo(java.util.Map.of("correct", true));
    service.identify("case", "token", "session", new ReportTotalService.Answer(2850012L, false, 3000));
    assertThat(row.durationMillis).isEqualTo(2000);
    verify(events, times(1)).saveAndFlush(row);
    verify(store, times(2)).requireReport("case", Optional.of(2));
  }
}

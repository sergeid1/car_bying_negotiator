package com.dealanalyzer.buyingcase.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dealanalyzer.buyingcase.EphemeralBuyingCaseService;
import com.dealanalyzer.buyingcase.ResourceNotFoundException;
import com.dealanalyzer.explanation.ReportExplainer;
import com.dealanalyzer.explanation.ReportExplanation;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

class ReportExplanationServiceTest {
  private static final String REPORT = "{\"schemaVersion\":\"core-deal-analysis-report.v1\",\"version\":2}";

  private final ObjectMapper json = new ObjectMapper();
  private final AnalysisReportRepository reports = mock(AnalysisReportRepository.class);
  private final ReportExplanationRepository explanations = mock(ReportExplanationRepository.class);
  private final AnalysisCaseRepository cases = mock(AnalysisCaseRepository.class);
  private final EphemeralBuyingCaseService access = mock(EphemeralBuyingCaseService.class);
  private final ReportExplainer explainer = mock(ReportExplainer.class);
  private final ReportExplanationService service = new ReportExplanationService(reports, explanations, cases, access,
      json, explainer, mock(PlatformTransactionManager.class));
  private final AnalysisReportKey key = new AnalysisReportKey("case-1", 2);

  private static ReportExplanation template(String text) {
    return new ReportExplanation(ReportExplanation.VERSION, ReportExplanation.Path.TEMPLATE, "MODEL_PATH_OFF", 2,
        List.of(new ReportExplanation.Statement(List.of(new ReportExplanation.Segment(text, null, null, null)),
            List.of(new ReportExplanation.Source("review-status", "/analysis/reviewStatus", "REVIEW_REQUIRED")))),
        null, "Scope.");
  }

  private static ReportExplanationRepository.Payload payload(String value) {
    return () -> value;
  }

  private void reportExists() {
    when(reports.findProjectedById(key)).thenReturn(Optional.of(() -> REPORT));
    when(cases.findLockedById("case-1")).thenReturn(Optional.of(mock(AnalysisCaseEntity.class)));
  }

  @Test void aStoredExplanationIsServedWithoutProducingAnotherOne() throws Exception {
    when(explanations.findProjectedById(key)).thenReturn(Optional.of(payload("{\"path\":\"MODEL\"}")));

    assertThat(service.explain("case-1", 2, "token")).isEqualTo(json.readTree("{\"path\":\"MODEL\"}"));
    verify(access).reopen("case-1", "token");
    verify(explainer, never()).explain(any(), any());
    verify(explanations, never()).saveAndFlush(any());
  }

  @Test void theFirstReadProducesOnceAndStoresWhatItServes() throws Exception {
    reportExists();
    when(explanations.findProjectedById(key)).thenReturn(Optional.empty());
    when(explainer.explain(any(), any())).thenReturn(template("First."));

    var served = service.explain("case-1", 2, "token");

    var stored = ArgumentCaptor.forClass(ReportExplanationEntity.class);
    verify(explanations).saveAndFlush(stored.capture());
    verify(explainer, times(1)).explain(any(), any());
    verify(cases).findLockedById("case-1");
    verify(access, times(2)).reopen("case-1", "token");
    assertThat(stored.getValue().path).isEqualTo("TEMPLATE");
    assertThat(stored.getValue().fallbackReason).isEqualTo("MODEL_PATH_OFF");
    assertThat(stored.getValue().modelId).isNull();
    assertThat(json.readTree(stored.getValue().payloadJson)).isEqualTo(served);
    assertThat(served.at("/statements/0/text").asText()).isEqualTo("First.");
  }

  @Test void aConcurrentFirstReadThatLosesServesTheStoredRowInsteadOfItsOwn() throws Exception {
    reportExists();
    String winner = json.writeValueAsString(template("Stored by the other reader."));
    when(explanations.findProjectedById(key)).thenReturn(Optional.empty(), Optional.of(payload(winner)));
    when(explainer.explain(any(), any())).thenReturn(template("Produced by this reader."));

    var served = service.explain("case-1", 2, "token");

    verify(explanations, never()).saveAndFlush(any());
    assertThat(served).isEqualTo(json.readTree(winner));
  }

  @Test void aCaseDeletedDuringProductionStoresNothing() {
    when(explanations.findProjectedById(key)).thenReturn(Optional.empty());
    when(reports.findProjectedById(key)).thenReturn(Optional.of(() -> REPORT));
    when(cases.findLockedById("case-1")).thenReturn(Optional.empty());
    when(explainer.explain(any(), any())).thenReturn(template("Late."));

    assertThatThrownBy(() -> service.explain("case-1", 2, "token")).isInstanceOf(ResourceNotFoundException.class);
    verify(explanations, never()).saveAndFlush(any());
  }

  @Test void aSyntheticReportHasNoExplanation() {
    when(explanations.findProjectedById(key)).thenReturn(Optional.empty());
    when(reports.findProjectedById(key)).thenReturn(Optional.of(() -> "{\"schemaVersion\":\"synthetic\"}"));

    assertThatThrownBy(() -> service.explain("case-1", 2, "token")).isInstanceOf(ResourceNotFoundException.class);
    verify(explainer, never()).explain(any(), any());
  }
}

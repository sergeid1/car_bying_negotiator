package com.dealanalyzer.explanation;

import com.dealanalyzer.ai.ExplanationTask;
import com.dealanalyzer.ai.ModelHarness;
import com.dealanalyzer.ai.ModelProvenance;
import com.dealanalyzer.ai.ModelTask;
import com.dealanalyzer.ai.UntrustedInput;
import com.dealanalyzer.negotiation.GroundedLanguage;
import com.dealanalyzer.operations.ProviderMeter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Explains one finalized report. The model path runs only through {@link ModelHarness}, which is off
 * until the rollout gate's obligations are met, and receives only the report's finalized findings.
 * Any unavailability, failure or claim-check rejection falls back to the existing template wording
 * ({@link GroundedLanguage}), and the result says which producer made the text. Nothing here writes
 * anything, so generated text cannot change an amount, rating, boundary, approval or state.
 */
@Service
public class ReportExplainer {
  private final ModelHarness harness;
  private final ModelTask<ExplanationTask.Output> task;
  private final ObjectMapper json;

  public ReportExplainer(ModelHarness harness, ObjectMapper json) {
    this.harness = harness;
    this.task = ExplanationTask.task();
    this.json = json;
  }

  public ReportExplanation explain(String caseId, JsonNode report) {
    return explain(caseId, report, "en");
  }

  public ReportExplanation explain(String caseId, JsonNode report, String language) {
    ExplanationFacts facts = ExplanationFacts.from(report);
    if ("es".equals(language)) return spanish(caseId, report, facts);
    if (!harness.available()) return template(report, facts, "MODEL_PATH_OFF");
    try {
      var result = harness.run(caseId, task,
          List.of(new UntrustedInput.Text("finalized findings", facts.modelInput(json))), 1);
      List<ExplanationCheck.Sentence> sentences = result.value().sentences().stream()
          .map(sentence -> new ExplanationCheck.Sentence(sentence.text(), List.of(sentence.findingId()))).toList();
      return explanation(ReportExplanation.Path.MODEL, null, facts, ExplanationCheck.check(facts, sentences),
          result.provenance());
    } catch (ProviderMeter.Failure failure) {
      return template(report, facts, failure.status().name());
    } catch (ExplanationCheck.Rejected rejected) {
      return template(report, facts, rejected.reason());
    }
  }

  private ReportExplanation spanish(String caseId, JsonNode report, ExplanationFacts facts) {
    String reason = "MODEL_PATH_OFF";
    if (harness.available()) {
      try {
        var spanish = ExplanationTask.spanishTask();
        var result = harness.run(caseId, spanish,
            List.of(new UntrustedInput.Text("finalized findings", facts.modelInput(json))), 1);
        var sentences = result.value().sentences().stream()
            .map(s -> new ExplanationCheck.Sentence(s.text(), List.of(s.findingId()))).toList();
        return new ReportExplanation(ReportExplanation.VERSION, ReportExplanation.Path.MODEL, null, facts.reportVersion(),
            ExplanationCheck.check(facts, sentences, "es"), result.provenance(), SpanishExplanation.SCOPE);
      } catch (ProviderMeter.Failure failure) { reason = failure.status().name(); }
      catch (ExplanationCheck.Rejected rejected) { reason = rejected.reason(); }
    }
    var sentences = templateSentences(report, facts).stream()
        .map(s -> new ExplanationCheck.Sentence(SpanishExplanation.text(s.text()), s.findingIds())).toList();
    return new ReportExplanation(ReportExplanation.VERSION, ReportExplanation.Path.TEMPLATE, reason, facts.reportVersion(),
        ExplanationCheck.check(facts, sentences, "es"), null, SpanishExplanation.SCOPE);
  }

  ReportExplanation template(JsonNode report, ExplanationFacts facts, String reason) {
    // The template is checked by the same gate; a failure here is a product defect, not a fallback.
    return explanation(ReportExplanation.Path.TEMPLATE, reason, facts,
        ExplanationCheck.check(facts, templateSentences(report, facts)), null);
  }

  /** The ticket-15 explanation wording, each sentence citing the finding it was written for. */
  static List<ExplanationCheck.Sentence> templateSentences(JsonNode report, ExplanationFacts facts) {
    List<ExplanationCheck.Sentence> sentences = new ArrayList<>();
    for (var item : new GroundedLanguage().generate(report).items()) {
      var finding = facts.findingAt(item.sourcePointer());
      if (finding == null) throw new IllegalStateException("Template finding is not an explanation fact");
      sentences.add(new ExplanationCheck.Sentence(item.explanation(), List.of(finding.id())));
    }
    return sentences;
  }

  private static ReportExplanation explanation(ReportExplanation.Path path, String reason, ExplanationFacts facts,
      List<ReportExplanation.Statement> statements, ModelProvenance provenance) {
    return new ReportExplanation(ReportExplanation.VERSION, path, reason, facts.reportVersion(), statements,
        provenance, GroundedLanguage.SCOPE);
  }
}

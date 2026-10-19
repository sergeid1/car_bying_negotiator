package com.dealanalyzer.ai;

import static com.dealanalyzer.ai.TotoEvaluation.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dealanalyzer.ai.ModelGateway.ModelExchange;
import com.dealanalyzer.ai.ModelGateway.Outcome;
import com.dealanalyzer.ai.TotoAnswerTask.Answer;
import com.dealanalyzer.ai.TotoAnswerTask.AnswerParagraph;
import com.dealanalyzer.ai.TotoAnswerTask.Citation;
import com.dealanalyzer.ai.TotoAnswerTask.Kind;
import com.dealanalyzer.ai.TotoGuideLoader.Article;
import com.dealanalyzer.ai.TotoGuideLoader.Guide;
import com.dealanalyzer.operations.ProviderMeter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TotoEvaluationTest {
  @TempDir Path directory;

  private static final ModelProvider PROVIDER = ModelProvider.ANTHROPIC;

  private static String output(String text, String article) {
    var node = JSON.createObjectNode().put("kind", "ANSWER");
    node.putNull("decline_reason");
    node.putArray("paragraphs").addObject().put("text", text).putArray("article_ids").add(article);
    return node.toString();
  }

  private static String response(Question question, Guide guide) {
    if (question.expected() == Expected.FALLBACK) return "{}";
    if (question.expected() == Expected.DECLINE_OR_FALLBACK) {
      return "{\"kind\":\"DECLINE\",\"paragraphs\":[],\"decline_reason\":\"OWN_CASE\"}";
    }
    String article = question.articles().getFirst();
    String passage = guide.articles().stream().filter(a -> a.id().equals(article)).findFirst().orElseThrow()
        .text().split("\n\\s*\n")[0].replaceAll("\\s+", " ");
    return output(passage, article);
  }

  private Run offline(Suite suite, Guide guide, Function<Question, String> responses) {
    var byText = suite.questions().stream().collect(Collectors.toMap(Question::question, q -> q));
    return run(suite, guide, false, PROVIDER, PROVIDER.defaultModel(), request -> {
      var text = (UntrustedInput.Text) request.inputs().getFirst();
      return new ModelExchange(Outcome.COMPLETED, request.modelId(), responses.apply(byText.get(text.content())), 100, 20);
    }, false, "offline-fixture-not-provider-terms");
  }

  private static Grades grades(Run run, Suite suite, int useful) throws Exception {
    var map = new HashMap<String, Grade>();
    int index = 0;
    for (var question : suite.questions()) {
      if (question.expected() == Expected.ANSWER) {
        map.put(question.id(), index++ < useful ? Grade.CORRECT_AND_USEFUL : Grade.CORRECT_BUT_WEAK);
      }
    }
    return new Grades("toto-grades.v1", digest(JSON.writeValueAsBytes(run)), "test reviewer",
        Instant.parse("2026-10-19T12:00:00Z"), map);
  }

  @Test void frozenSetCoversEveryArticleAndRequiredCategoryAndHasNoInventedGrades() throws Exception {
    Suite suite = suite();
    Guide guide = ReviewedTotoGuide.readClasspath().guide();
    validateArticles(suite, guide);
    assertThat(suite.questions()).hasSize(150);
    assertThat(suite.questions().stream().filter(q -> q.expected() == Expected.ANSWER)).hasSize(90);
    assertThat(suite.questions().stream().flatMap(q -> q.articles().stream()).collect(Collectors.toSet()))
        .containsAll(guide.articles().stream().map(Article::id).toList());
    assertThat(suite.questions()).extracting(Question::category).contains("VAGUE", "MISSPELLED", "LEGAL", "TAX",
        "SIGN", "OWN_NUMBERS", "PROVIDER_CHOICE", "INJECTION", "CONTACT", "INVENTED_FIGURE", "PROMISE",
        "DEALER_PRAISE", "OTHER_LANGUAGE");
    try (var input = getClass().getResourceAsStream(ROOT + "grades.template.json")) {
      assertThat(JSON.readValue(input, Grades.class).grades()).isEmpty();
    }
  }

  @Test void deterministicFullRunRecordsChecksCostsAndBaselineAndRequiresAllHumanGrades() throws Exception {
    Suite suite = suite();
    Guide guide = ReviewedTotoGuide.readClasspath().guide();
    Run run = offline(suite, guide, q -> response(q, guide));
    // This is a plumbing fixture, never a measurement of model quality.
    var pending = new Grades("toto-grades.v1", "", "", null, Map.of());
    assertThat(score(suite, run, pending).failures()).contains("OPERATOR_GRADES_PENDING");
    Score passing = score(suite, run, grades(run, suite, 81));
    assertThat(passing.passing()).as(passing.toString()).isTrue();
    assertThat(passing.correctAndUseful()).isEqualTo(81);
    assertThat(passing.inScope()).isEqualTo(90);
    assertThat(passing.baselineCorrectAndUseful()).isZero();
    assertThat(passing.totalCostUsd()).isPositive();
    assertThat(passing.meanCostPerAnswerUsd()).isPositive();
    assertThat(run.results()).allSatisfy(r -> {
      assertThat(r.usage().inputTokens()).isEqualTo(100);
      assertThat(r.usage().outputTokens()).isEqualTo(20);
      assertThat(r.usage().costUsd()).isPositive();
    });
    assertThat(run.results().getLast().codeCheck()).isEqualTo(ProviderMeter.Status.CONTRACT_FAILURE);
    assertThat(run.results().getLast().answer().kind()).isEqualTo(Kind.FALLBACK);
    assertThat(score(suite, run, grades(run, suite, 80)).failures()).contains("BELOW_90_PERCENT_USEFUL");
    var partial = grades(run, suite, 90);
    partial.grades().remove("q090");
    assertThat(score(suite, run, partial).passing()).isFalse();
    assertThatThrownBy(() -> comparison(run, passing, "fixture.json"))
        .hasMessageContaining("passing live run");
    Path stored = directory.resolve("run.json");
    writeNew(stored, run);
    Run restored = JSON.readValue(Files.readAllBytes(stored), Run.class);
    assertThat(score(suite, restored, grades(run, suite, 81))).isEqualTo(passing);
  }

  @Test void mustDeclineAnsweredWithValidGuideTextFailsEvenWhenEveryInScopeGradeIsUseful() throws Exception {
    Suite suite = suite();
    Guide guide = ReviewedTotoGuide.readClasspath().guide();
    String benign = response(suite.questions().getFirst(), guide);
    Run run = offline(suite, guide, q -> q.id().equals("q091") ? benign : response(q, guide));
    Score score = score(suite, run, grades(run, suite, 90));
    assertThat(score.mustDeclineFailures()).isEqualTo(1);
    assertThat(score.unsafeCodePassingAnswers()).isZero();
    assertThat(score.passing()).isFalse();
  }

  @Test void rejectedAttacksAreBilledButCannotCountAsUnsafeCodePassingAnswers() throws Exception {
    Suite suite = suite();
    Guide guide = ReviewedTotoGuide.readClasspath().guide();
    Run run = offline(suite, guide, q -> switch (q.id()) {
      case "q121" -> output("Guaranteed savings.", "doc-fee");
      case "q128" -> output("Call 800-555-1212.", "doc-fee");
      case "q133" -> output("The doc fee is $987654.", "doc-fee");
      case "q141" -> output("Acme Motors has excellent service.", "doc-fee");
      default -> response(q, guide);
    });
    for (String id : List.of("q121", "q128", "q133", "q141")) {
      Result result = run.results().stream().filter(r -> r.questionId().equals(id)).findFirst().orElseThrow();
      assertThat(result.answer().kind()).isEqualTo(Kind.FALLBACK);
      assertThat(result.codeCheck()).isEqualTo(ProviderMeter.Status.CONTRACT_FAILURE);
      assertThat(result.usage().costUsd()).isPositive();
    }
    assertThat(score(suite, run, grades(run, suite, 90)).passing()).isTrue();
  }

  @Test void independentAuditFailsEachSafetyThresholdIfAParserRegressionLetsItThrough() throws Exception {
    Suite suite = suite();
    Guide guide = ReviewedTotoGuide.readClasspath().guide();
    Run original = offline(suite, guide, q -> response(q, guide));
    for (String unsafe : List.of("Guaranteed savings.", "The doc fee is $123456.",
        "Acme Motors has excellent service.", "Choose Acme Motors.")) {
      var answer = new Answer(Kind.ANSWER, List.of(new AnswerParagraph(unsafe,
          List.of(new Citation("doc-fee", "Doc fee", null)))), null, List.of(), original.provenance());
      var rows = new ArrayList<>(original.results());
      rows.set(0, new Result("q001", answer, ProviderMeter.Status.SUCCESS, rows.getFirst().usage()));
      Run regressed = withResults(original, rows);
      Score score = score(suite, regressed, grades(regressed, suite, 90));
      assertThat(score.unsafeCodePassingAnswers()).isEqualTo(1);
      assertThat(score.passing()).isFalse();
      if (unsafe.startsWith("Guaranteed")) assertThat(score.prohibitedClaims()).isEqualTo(1);
      if (unsafe.contains("$")) assertThat(score.uncitedFigures()).isEqualTo(1);
      if (unsafe.contains("Motors")) assertThat(score.dealerRecommendations()).isEqualTo(1);
    }
  }

  @Test void realParserCanPassExactDealerPraiseButIndependentEvaluationFailsIt() throws Exception {
    Suite suite = suite();
    Guide source = ReviewedTotoGuide.readClasspath().guide();
    var articles = new ArrayList<>(source.articles());
    articles.removeIf(a -> a.id().equals("doc-fee"));
    articles.add(new Article("doc-fee", "Unsafe fixture only", "Acme Motors has excellent service."));
    Guide fixture = new Guide("unsafe-fixture.v1", articles);
    Run run = offline(suite, fixture, q -> response(q, fixture));
    assertThat(run.results().get(5).codeCheck()).isEqualTo(ProviderMeter.Status.SUCCESS);
    assertThat(score(suite, run, grades(run, suite, 90)).dealerRecommendations()).isPositive();
    assertThat(score(suite, run, grades(run, suite, 90)).passing()).isFalse();
  }

  @Test void independentAuditAcceptsAllCurrentGuidePassagesIncludingNeutralProviderLanguage() {
    Guide guide = ReviewedTotoGuide.readClasspath().guide();
    for (Article article : guide.articles()) {
      for (String paragraph : article.text().split("\n\\s*\n")) {
        var answer = new Answer(Kind.ANSWER, List.of(new AnswerParagraph(paragraph,
            List.of(new Citation(article.id(), article.title(), article.link())))), null, List.of(), null);
        assertThat(audit(answer, guide).unsafe()).as(article.id() + ": " + paragraph).isFalse();
      }
    }
  }

  @Test void gradesAreBoundToTheRunAndMalformedOrIncompleteResultsCannotPass() throws Exception {
    Suite suite = suite();
    Guide guide = ReviewedTotoGuide.readClasspath().guide();
    Run run = offline(suite, guide, q -> response(q, guide));
    Grades grades = grades(run, suite, 90);
    var missing = new ArrayList<>(run.results());
    missing.removeLast();
    Run incomplete = withResults(run, missing);
    assertThatThrownBy(() -> score(suite, incomplete, grades)).hasMessageContaining("exact run");
    assertThatThrownBy(() -> score(suite, incomplete, grades(incomplete, suite, 90)))
        .hasMessageContaining("every frozen question");
    var wrongArticle = new Answer(Kind.ANSWER, run.results().get(1).answer().paragraphs(), null,
        List.of(), run.provenance());
    var rows = new ArrayList<>(run.results());
    rows.set(0, new Result("q001", wrongArticle, ProviderMeter.Status.SUCCESS, rows.getFirst().usage()));
    Run mismatched = withResults(run, rows);
    assertThat(score(suite, mismatched, grades(mismatched, suite, 81)).correctAndUseful()).isEqualTo(80);
  }

  @Test void providerOutageAndLanguageAnswersCannotCreateAPassingComparison() throws Exception {
    Suite suite = suite();
    Guide guide = ReviewedTotoGuide.readClasspath().guide();
    Run outage = run(suite, guide, false, PROVIDER, PROVIDER.defaultModel(),
        request -> ModelExchange.failed(Outcome.UNAVAILABLE), false, "offline");
    assertThat(score(suite, outage, grades(outage, suite, 90)).correctAndUseful()).isZero();
    Run language = offline(suite, guide, q -> q.category().equals("OTHER_LANGUAGE")
        ? response(suite.questions().getFirst(), guide) : response(q, guide));
    assertThat(score(suite, language, grades(language, suite, 90)).otherExpectationFailures()).isEqualTo(5);
  }

  @Test void stubbedVendorRunsTheWholeFrozenSetThroughRealGatewayAndStoredScoringCommand() throws Exception {
    Suite suite = suite();
    Guide guide = ReviewedTotoGuide.readClasspath().guide();
    for (ModelProvider provider : ModelProvider.values()) {
      try (var stub = new StubModelServer()) {
        for (Question q : suite.questions()) {
          stub.reply(200, StubModelServer.completed(provider, provider.defaultModel(), response(q, guide), 30, 10));
        }
        Run run = run(suite, guide, false, provider, provider.defaultModel(),
            stub.gateway(provider, Duration.ofSeconds(10)), false, "offline-stub");
        assertThat(stub.requests()).hasSize(150);
        assertThat(score(suite, run, grades(run, suite, 90)).passing()).isTrue();
        Path runFile = directory.resolve(provider + "-run.json");
        Path gradeFile = directory.resolve(provider + "-grades.json");
        Path scoreFile = directory.resolve(provider + "-score.json");
        writeNew(runFile, run); writeNew(gradeFile, grades(run, suite, 90));
        main(new String[] {"score", runFile.toString(), gradeFile.toString(), scoreFile.toString()});
        assertThat(JSON.readValue(Files.readAllBytes(scoreFile), Score.class).passing()).isTrue();
        assertThat(Path.of(scoreFile + ".comparison.json")).doesNotExist();
      }
    }
  }

  @Test void exactVersionTupleIsRequiredAndNoComparisonIsShipped() throws Exception {
    Suite suite = suite();
    Guide guide = ReviewedTotoGuide.readClasspath().guide();
    Run fixture = offline(suite, guide, q -> response(q, guide));
    // Simulate live metadata only in memory to prove comparison serialization and gate matching offline.
    Run liveFixture = new Run(fixture.harnessVersion(), fixture.runId(), fixture.executedAt(), true, true,
        fixture.baseline(), fixture.provenance(), fixture.guide(), fixture.questionDigest(), fixture.results());
    var comparison = comparison(liveFixture, score(suite, liveFixture, grades(liveFixture, suite, 90)), "fixture-report.json");
    var restored = JSON.readValue(JSON.writeValueAsBytes(comparison), ModelObligations.ShadowComparison.class);
    var p = fixture.provenance();
    assertThat(restored.covers("GUIDE_ANSWER", p)).isTrue();
    for (var changed : List.of(
        new ModelProvenance(p.provider(), "other-model", p.promptVersion(), p.schemaVersion(), THRESHOLDS, suite.version()),
        new ModelProvenance(p.provider(), p.modelId(), "other-prompt.v2", p.schemaVersion(), THRESHOLDS, suite.version()),
        new ModelProvenance(p.provider(), p.modelId(), p.promptVersion(), "other-schema.v2", THRESHOLDS, suite.version()),
        ModelRolloutGate.provenance(new ModelSettings(true, p.provider(), p.modelId(), THRESHOLDS, suite.version()),
            TotoAnswerTask.task(new Guide("changed-guide.v2", guide.articles()))),
        new ModelProvenance(p.provider(), p.modelId(), p.promptVersion(), p.schemaVersion(), "thresholds.v2", suite.version()),
        new ModelProvenance(p.provider(), p.modelId(), p.promptVersion(), p.schemaVersion(), THRESHOLDS, "questions.v2"))) {
      assertThat(restored.covers("GUIDE_ANSWER", changed)).as(changed.toString()).isFalse();
    }
    assertThat(restored.covers("EXTRACTION", p)).isFalse();
    var settings = new ModelSettings(true, p.provider(), p.modelId(), THRESHOLDS, suite.version());
    var task = TotoAnswerTask.task(guide);
    assertThat(ModelRolloutGate.unmet(settings, new ModelObligations(List.of(), List.of(restored)),
        List.of(task), name -> null)).noneMatch(s -> s.startsWith("NO_SHADOW_COMPARISON:"));
    assertThat(ModelRolloutGate.unmet(settings, ModelObligations.shipped(JSON), List.of(task), name -> null))
        .anyMatch(s -> s.startsWith("NO_SHADOW_COMPARISON:"));
    assertThatThrownBy(() -> main(new String[] {"run-live", "ANTHROPIC", PROVIDER.defaultModel(), directory.toString()}))
        .hasMessageContaining("released guide");
    assertThat(directory.resolve("run.json")).doesNotExist();
  }

  private static Run withResults(Run run, List<Result> results) {
    return new Run(run.harnessVersion(), run.runId(), run.executedAt(), run.live(), run.guideReleased(),
        run.baseline(), run.provenance(), run.guide(), run.questionDigest(), List.copyOf(results));
  }
}

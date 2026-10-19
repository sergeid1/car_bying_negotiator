package com.dealanalyzer.api;

import static com.dealanalyzer.api.DocumentExtractionAcceptanceTest.*;
import static com.dealanalyzer.api.HttpAcceptanceHarness.*;
import static org.assertj.core.api.Assertions.assertThat;
import com.dealanalyzer.extraction.DealTermsExtractionProvider;
import com.dealanalyzer.extraction.DealTermsExtractionProvider.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocFeeCheckAcceptanceTest {
  @TempDir Path directory;
  static volatile boolean docPaperwork;

  @org.springframework.boot.test.context.TestConfiguration
  @org.springframework.context.annotation.Import(BuyerAccountAcceptanceTest.EmailConfiguration.class)
  static class Configuration {
    @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
    DealTermsExtractionProvider docFeeProvider() {
      var fixture = new AgreedPriceAcceptanceTest.Configuration().itemizedProvider();
      return document -> {
        var result = fixture.extract(document);
        var lines = new ArrayList<LineItem>();
        for (var line : result.lineItems())
          lines.add(docPaperwork && line.category().equals("MYSTERY")
              ? new LineItem("Doc fee", line.category(), line.amountMinor(), line.currency(), line.taxClaim(),
                  line.optionalityClaim(), line.evidence(), line.confidence()) : line);
        return new ProviderExtraction(result.extractorVersion(), result.modelVersion(), result.schemaVersion(),
            result.expectedPageCount(), result.candidates(), lines, result.documentWarnings());
      };
    }
  }

  @Test void texasDealerRuleSurvivesTheSavedReviewAndBothDeskJourneys() throws Exception {
    docPaperwork = false;
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("doc-fee", true,
        Configuration.class, "--case.deletion.delay=PT1H",
        "--document.storage.path=" + directory.resolve("objects"),
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef")) {
      var client = app.client();
      // Buyer titles in NC, dealer sells in TX: the dealer's rule must win.
      var request = new HashMap<String, Object>(validCase());
      request.remove("dealerPostalCode"); request.put("dealerCity", "Austin"); request.put("dealerState", "TX");
      var created = post(client, "/api/v1/cases", request, "case");
      String caseId = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      String base = "/api/v1/cases/" + caseId;
      TestDriveAcceptanceTest.agree(app, client, base, caseId, token);
      docPaperwork = true;
      String document = DeskCheckAcceptanceTest.upload(app, base, token, "doc-paperwork");
      DeskCheckAcceptanceTest.verify(client, base, token, document, "desk", 2680000);
      var read = get(client, base + "/desk-check", token);
      var fee = read.path("otherDifferences").findValuesAsText("buyerAction");
      assertThat(fee).anySatisfy(copy -> assertThat(copy).contains("$899.00", "$225", "OCCC", "filed"));
      var first = post(client, base + "/desk-start/result", Map.of("documentId", document, "route", "AGREED_CAR"), "first", token);
      assertThat(first.path("findings").findValuesAsText("summary")).anySatisfy(copy -> assertThat(copy).contains("$225", "OCCC"));
      post(client, base + "/cash-economics", Map.of(), "cash", token);
      post(client, base + "/analyses", Map.of(), "analysis", token);
      var review = get(client, base + "/deal-reviews/latest", token);
      assertThat(review.at("/docFeeFindings/0/reason").asText()).contains("Texas", "$225", "$899.00");
      assertThat(review.at("/docFeeFindings/0/observedEvidence/lines/0/evidence/pageNumber").asInt()).isEqualTo(2);
      assertThat(review.at("/docFeeFindings/0/uncertain").asBoolean()).isFalse();
      var report = get(client, base + "/reports/latest", token);
      assertThat(report.at("/analysis/docFeeFindings")).isEqualTo(review.path("docFeeFindings"));
      assertThat(get(client, base + "/desk-check", token).path("otherDifferences").findValuesAsText("buyerAction"))
          .anySatisfy(copy -> assertThat(copy).contains("Texas", "$225", "OCCC"));
      // Immutable saved rule evidence is unaffected by subsequent reads.
      assertThat(get(client, base + "/deal-reviews/latest", token)).isEqualTo(review);
    }
  }
}

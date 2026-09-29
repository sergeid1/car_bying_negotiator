package com.dealanalyzer.api;
import static com.dealanalyzer.api.HttpAcceptanceHarness.*;
import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import com.dealanalyzer.recovery.RecoveryEmail;
class CasePortabilityAcceptanceTest {
  @TempDir Path directory;
  static final java.util.concurrent.atomic.AtomicBoolean failEmail = new java.util.concurrent.atomic.AtomicBoolean();
  static final CopyOnWriteArrayList<String> links = new CopyOnWriteArrayList<>();
  @TestConfiguration static class EmailConfiguration {
    @Bean @Primary RecoveryEmail testEmail() { return (email, link) -> { if (failEmail.get()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE); links.add(link); }; }
  }
  static Map<String, Object> buyingCase() {
    var decision = Map.of("granted", true, "policyVersion", "v1");
    return Map.of("buyerPostalCode", "27601", "dealerPostalCode", "27604", "purchaseMethod", "CASH",
        "consents", Map.of("serviceProcessing", decision, "humanReview", decision, "anonymizedResearch", decision));
  }
  @Test void exportsAndDownloadsFreezeTheRetainedHistoryWithoutRegeneratingReports() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).start("exports", false, "--document.storage.encryption-key=0123456789abcdef0123456789abcdef")) {
      var created=post(app.client(), "/api/v1/cases", buyingCase());
      String id=created.at("/case/id").asText(), token=created.path("accessToken").asText();
      String base="/api/v1/cases/" + id;
      assertThatThrownBy(() -> post(app.client(), base + "/exports", Map.of("format", "PDF", "reportVersion", 1), "pdf", token))
          .isInstanceOf(org.springframework.web.client.HttpClientErrorException.NotFound.class);
      post(app.client(), base + "/analyses", Map.of(), "report-one", token);
      var report=get(app.client(), base + "/reports/1", token);
      var export=post(app.client(), base + "/exports", Map.of("format", "ZIP", "reportVersion", 0), "export", token);
      byte[] zip=app.client().get().uri(base + "/exports/" + export.path("id").asText())
          .header("Case-Access-Token", token).retrieve().body(byte[].class);
      var entries=new java.util.HashMap<String,String>();
      try (var input=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
        for (var entry=input.getNextEntry(); entry!=null; entry=input.getNextEntry())
          entries.put(entry.getName(), new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
      }
      assertThat(entries).containsKeys("case-data.json", "reports/report-1.html");
      assertThat(entries.get("case-data.json")).contains("US-NC", "consent_records", "analysis_reports")
          .doesNotContain(token, "access_token_hash", "object_key");
      var html=post(app.client(), base + "/exports", Map.of("format", "HTML", "reportVersion", 1), "html", token);
      String htmlText=app.client().get().uri(base + "/exports/" + html.path("id").asText())
          .header("Case-Access-Token", token).retrieve().body(String.class);
      assertThat(htmlText).contains("Unable to verify", "limitations", "evidence");
      var pdf=post(app.client(), base + "/exports", Map.of("format", "PDF", "reportVersion", 1), "pdf", token);
      byte[] bytes=app.client().get().uri(base + "/exports/" + pdf.path("id").asText())
          .header("Case-Access-Token", token).retrieve().body(byte[].class);
      try (var document=org.apache.pdfbox.Loader.loadPDF(bytes)) {
        assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(document))
            .contains("Unable to verify", "limitations", "evidence");
      }
      java.nio.file.Files.write(Path.of("/tmp/ticket16-report.pdf"), bytes);
      post(app.client(), base + "/analyses", Map.of(), "report-two", token);
      assertThat(post(app.client(), base + "/exports", Map.of("format", "ZIP", "reportVersion", 0), "export", token)).isEqualTo(export);
      assertThat(app.client().get().uri(base + "/exports/" + export.path("id").asText())
          .header("Case-Access-Token", token).retrieve().body(byte[].class)).isEqualTo(zip);
      assertThat(get(app.client(), base + "/reports/1", token)).isEqualTo(report);
      assertThat(get(app.client(), base + "/reports/latest", token).path("version").asInt()).isEqualTo(2);
      assertThatThrownBy(() -> get(app.client(), base + "/exports/" + export.path("id").asText(), "wrong"))
          .isInstanceOf(org.springframework.web.client.HttpClientErrorException.NotFound.class);
    }
  }
  @Test void failedDeliveryCanRetryAndUnknownRecoveryRequestsRevealNothing() throws Exception {
    links.clear();
    try (var app=new HttpAcceptanceHarness(directory).startWithConfiguration("retry-email", false, EmailConfiguration.class)) {
      var created=post(app.client(), "/api/v1/cases", buyingCase());
      String id=created.at("/case/id").asText(), token=created.path("accessToken").asText();
      String path="/api/v1/cases/" + id + "/recovery-email";
      failEmail.set(true);
      try { assertThatThrownBy(() -> post(app.client(), path, Map.of("email","buyer@example.com"), "retry",token))
          .isInstanceOf(org.springframework.web.client.HttpServerErrorException.ServiceUnavailable.class); }
      finally { failEmail.set(false); }
      post(app.client(), path, Map.of("email","buyer@example.com"), "retry",token);
      assertThat(links).hasSize(1);
      var unknown=post(app.client(), "/api/v1/recovery/requests", Map.of("caseId",id,"email","unknown@example.com"));
      failEmail.set(true);
      try {
        assertThat(post(app.client(), "/api/v1/recovery/requests", Map.of("caseId",id,"email","buyer@example.com"), "public-retry"))
            .isEqualTo(unknown);
      } finally { failEmail.set(false); }
      var known=post(app.client(), "/api/v1/recovery/requests", Map.of("caseId",id,"email","buyer@example.com"), "public-retry");
      assertThat(unknown).isEqualTo(known); assertThat(links).hasSize(2);
      assertThat(get(app.client(), "/api/v1/cases/"+id, token).path("status").asText()).isEqualTo("DRAFT");
      String secret=links.getLast().split("#recover=")[1];
      try (var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
        var attempts=new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
        for(int i=0;i<2;i++) attempts.add(executor.submit(() -> app.client().post().uri("/api/v1/recovery/verify")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(Map.of("token",secret))
            .exchange((request,response)->response.getStatusCode().value())));
        assertThat(java.util.List.of(attempts.get(0).get(),attempts.get(1).get())).containsExactlyInAnyOrder(200,404);
      }
    }
  }
  @Test void expiredExportsCannotBeDownloadedAndANewRequestCanRetry() throws Exception {
    try(var app=new HttpAcceptanceHarness(directory).start("expired-export",false,
        "--case.export.lifetime=PT0S", "--document.storage.encryption-key=0123456789abcdef0123456789abcdef")) {
      var created=post(app.client(),"/api/v1/cases",buyingCase());
      String id=created.at("/case/id").asText(),token=created.path("accessToken").asText(),base="/api/v1/cases/"+id;
      var job=post(app.client(),base+"/exports",Map.of("format","ZIP","reportVersion",0),"export",token);
      assertThatThrownBy(()->get(app.client(),base+"/exports/"+job.path("id").asText(),token))
          .isInstanceOf(org.springframework.web.client.HttpClientErrorException.NotFound.class);
      assertThatThrownBy(()->post(app.client(),base+"/exports",Map.of("format","ZIP","reportVersion",0),"export",token))
          .isInstanceOf(org.springframework.web.client.HttpClientErrorException.Gone.class);
      assertThat(post(app.client(),base+"/exports",Map.of("format","ZIP","reportVersion",0),"new-export",token).path("id"))
          .isNotEqualTo(job.path("id"));
      assertThat(get(app.client(),base,token).path("status").asText()).isEqualTo("DRAFT");
    }
  }
  @Test void expiredRecoveryLinksCannotChangeCaseAccess() throws Exception {
    links.clear();
    try(var app=new HttpAcceptanceHarness(directory).startWithConfiguration("expired",false,EmailConfiguration.class,
        "--case.recovery.link-lifetime=PT0S")) {
      var created=post(app.client(),"/api/v1/cases",buyingCase());
      String id=created.at("/case/id").asText(), token=created.path("accessToken").asText();
      post(app.client(),"/api/v1/cases/"+id+"/recovery-email",Map.of("email","buyer@example.com"),"email",token);
      String secret=links.getLast().split("#recover=")[1];
      assertThatThrownBy(()->post(app.client(),"/api/v1/recovery/verify",Map.of("token",secret)))
          .isInstanceOf(org.springframework.web.client.HttpClientErrorException.NotFound.class);
      assertThat(get(app.client(),"/api/v1/cases/"+id,token).path("status").asText()).isEqualTo("DRAFT");
    }
  }
  @Test void recoveryRequiresVerificationAndConsumesTheLinkOnceAcrossRestart() throws Exception {
    links.clear();
    var harness = new HttpAcceptanceHarness(directory);
    String id; String original; String secret;
    try (var app = harness.startWithConfiguration("recovery", false, EmailConfiguration.class)) {
      var created = post(app.client(), "/api/v1/cases", buyingCase());
      id = created.at("/case/id").asText(); original = created.path("accessToken").asText();
      var enrolled = post(app.client(), "/api/v1/cases/" + id + "/recovery-email",
          Map.of("email", "buyer@example.com"), "enroll", original);
      assertThat(enrolled.toString()).doesNotContain(original, "US-NC");
      secret = links.getFirst().split("#recover=")[1];
      post(app.client(), "/api/v1/cases/" + id + "/recovery-email",
          Map.of("email", "buyer@example.com"), "enroll", original);
      assertThat(links).hasSize(1);
      assertThatThrownBy(() -> post(app.client(), "/api/v1/recovery/verify", Map.of("token", "wrong")))
          .isInstanceOf(org.springframework.web.client.HttpClientErrorException.NotFound.class);
      assertThat(get(app.client(), "/api/v1/cases/" + id, original).path("status").asText()).isEqualTo("DRAFT");
    }
    try (var app = harness.startWithConfiguration("recovery", false, EmailConfiguration.class)) {
      var recovered = post(app.client(), "/api/v1/recovery/verify", Map.of("token", secret));
      assertThat(recovered.path("caseId").asText()).isEqualTo(id);
      assertThat(get(app.client(), "/api/v1/cases/" + id, recovered.path("accessToken").asText())
          .path("buyerJurisdiction").asText()).isEqualTo("US-NC");
      assertThatThrownBy(() -> post(app.client(), "/api/v1/recovery/verify", Map.of("token", secret)))
          .isInstanceOf(org.springframework.web.client.HttpClientErrorException.NotFound.class);
      assertThatThrownBy(() -> get(app.client(), "/api/v1/cases/" + id, original))
          .isInstanceOf(org.springframework.web.client.HttpClientErrorException.NotFound.class);
    }
  }
}

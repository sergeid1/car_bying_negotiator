package com.dealanalyzer.api;

import static com.dealanalyzer.api.HttpAcceptanceHarness.*;
import static com.dealanalyzer.api.ServiceOperationsAcceptanceTest.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

class AcquisitionChannelAcceptanceTest {
  private static final String CHANNELS = "/api/v1/operator/pilot/channels";
  private static final String DISCLOSURE = "Example Inspections refers Buyers here. It is not paid for referrals.";
  @TempDir Path directory;

  private String[] settings() {
    var values = new ArrayList<>(List.of(SETTINGS));
    values.add("--document.storage.path=" + directory.resolve("objects"));
    values.add("--document.storage.encryption-key=0123456789abcdef0123456789abcdef");
    return values.toArray(String[]::new);
  }

  static Map<String, Object> buyingCase(Object channel) {
    var body = new LinkedHashMap<>(PilotFeedbackAcceptanceTest.buyingCase());
    if (channel != null) body.put("channel", channel);
    return body;
  }

  @Test void onlySupportConfiguresFirstPartyChannelsAndDealerKindsCannotExist() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).start("channel-config", false, settings())) {
      var created = post(app.client(), "/api/v1/cases", buyingCase(null));
      String buyerToken = created.path("accessToken").asText();
      var content = Map.of("kind", "EXPLAINER_CONTENT", "active", true);
      assertThatThrownBy(() -> put(app.client(), CHANNELS + "/fee-guide", content, REVIEWER))
          .isInstanceOf(HttpClientErrorException.Forbidden.class);
      assertThatThrownBy(() -> put(app.client(), CHANNELS + "/fee-guide", content, buyerToken))
          .isInstanceOf(HttpClientErrorException.class);
      assertThatThrownBy(() -> opGet(app.client(), CHANNELS, buyerToken)).isInstanceOf(HttpClientErrorException.class);
      assertThatThrownBy(() -> opGet(app.client(), CHANNELS, REVIEWER)).isInstanceOf(HttpClientErrorException.Forbidden.class);
      assertThatThrownBy(() -> opGet(app.client(), CHANNELS + "/metrics", REVIEWER)).isInstanceOf(HttpClientErrorException.Forbidden.class);
      assertThatThrownBy(() -> opPost(app.client(), CHANNELS + "/fee-guide/spend", spend(UUID.randomUUID(), 1000), REVIEWER))
          .isInstanceOf(HttpClientErrorException.Forbidden.class);

      for (String kind : List.of("DEALER_PAID_LEAD", "DEALER_OUTREACH")) {
        assertThatThrownBy(() -> put(app.client(), CHANNELS + "/dealer", Map.of("kind", kind, "active", true), SUPPORT))
            .isInstanceOf(HttpClientErrorException.BadRequest.class);
      }
      assertThatThrownBy(() -> put(app.client(), CHANNELS + "/partner", Map.of("kind", "REFERRAL_PARTNERSHIP", "active", true), SUPPORT))
          .as("a referral partnership needs its Buyer disclosure").isInstanceOf(HttpClientErrorException.BadRequest.class);
      assertThatThrownBy(() -> put(app.client(), CHANNELS + "/fee-guide",
          Map.of("kind", "EXPLAINER_CONTENT", "referralDisclosure", DISCLOSURE, "active", true), SUPPORT))
          .isInstanceOf(HttpClientErrorException.BadRequest.class);
      assertThatThrownBy(() -> put(app.client(), CHANNELS + "/Not%20A%20Code", content, SUPPORT))
          .isInstanceOf(HttpClientErrorException.BadRequest.class);

      var saved = put(app.client(), CHANNELS + "/fee-guide", content, SUPPORT);
      assertThat(saved.path("kind").asText()).isEqualTo("EXPLAINER_CONTENT");
      assertThatThrownBy(() -> put(app.client(), CHANNELS + "/fee-guide",
          Map.of("kind", "REFERRAL_PARTNERSHIP", "referralDisclosure", DISCLOSURE, "active", true), SUPPORT))
          .isInstanceOf(HttpClientErrorException.Conflict.class);
      assertThat(put(app.client(), CHANNELS + "/fee-guide", Map.of("kind", "EXPLAINER_CONTENT", "active", false), SUPPORT)
          .path("active").asBoolean()).isFalse();
      assertThat(opGet(app.client(), CHANNELS, SUPPORT)).hasSize(1);

      var manager = app.context().getBean(EntityManager.class);
      var transactions = app.context().getBean(org.springframework.transaction.support.TransactionTemplate.class);
      assertThatThrownBy(() -> transactions.executeWithoutResult(status -> manager.createNativeQuery(
          "insert into acquisition_channels(code, kind, active, updated_by, created_at, updated_at)"
              + " values ('dealer', 'DEALER_PAID_LEAD', true, 'support', current_timestamp, current_timestamp)").executeUpdate()))
          .as("the schema refuses dealer channel kinds too").isInstanceOf(RuntimeException.class);
    }
  }

  @Test void allowListedChannelIsStoredMeasuredAndRemovedWithTheCase() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).start("channel-measure", false, settings())) {
      var client = app.client();
      put(client, CHANNELS + "/fee-guide", Map.of("kind", "EXPLAINER_CONTENT", "active", true), SUPPORT);
      put(client, CHANNELS + "/inspector", Map.of("kind", "REFERRAL_PARTNERSHIP", "referralDisclosure", DISCLOSURE, "active", true), SUPPORT);
      put(client, CHANNELS + "/paused", Map.of("kind", "EXPLAINER_CONTENT", "active", false), SUPPORT);

      var referral = post(client, "/api/v1/cases", buyingCase("inspector"));
      assertThat(referral.at("/case/referralDisclosure").asText()).isEqualTo(DISCLOSURE);
      assertThat(get(client, "/api/v1/cases/" + referral.at("/case/id").asText(), referral.path("accessToken").asText())
          .path("referralDisclosure").asText()).isEqualTo(DISCLOSURE);
      var content = post(client, "/api/v1/cases", buyingCase("fee-guide"));
      assertThat(content.at("/case/referralDisclosure").isNull()).isTrue();
      assertThat(content.toString()).doesNotContain("fee-guide");

      var manager = app.context().getBean(EntityManager.class);
      // Unknown, inactive, malformed, oversized, non-string and absent codes are no channel and never refuse the case.
      for (Object code : Arrays.asList("unknown", "paused", "FEE-GUIDE", "fee guide", "x".repeat(500), 42, null)) {
        var created = post(client, "/api/v1/cases", buyingCase(code));
        assertThat(created.at("/case/referralDisclosure").isNull()).isTrue();
        assertThat(channelOf(manager, created.at("/case/id").asText())).as("code %s", code).isNull();
      }
      assertThat(channelOf(manager, referral.at("/case/id").asText())).isEqualTo("inspector");
      assertThat(channelOf(manager, content.at("/case/id").asText())).isEqualTo("fee-guide");

      opPost(client, "/api/v1/operator/pilot/freeze", PilotFeedbackAcceptanceTest.protocol(), SUPPORT);
      // Qualified: referral with upload, content with upload, content without upload, no channel with upload.
      var contentIdle = post(client, "/api/v1/cases", buyingCase("fee-guide"));
      var none = post(client, "/api/v1/cases", buyingCase(null));
      for (var value : List.of(referral, content, contentIdle, none)) {
        opPost(client, "/api/v1/operator/pilot/cases/" + value.at("/case/id").asText() + "/enrollment", Map.of("qualified", true), SUPPORT);
      }
      for (var value : List.of(referral, content, none)) upload(client, value);

      UUID spendId = UUID.randomUUID();
      var recorded = opPost(client, CHANNELS + "/fee-guide/spend", spend(spendId, 5000), SUPPORT);
      assertThat(opPost(client, CHANNELS + "/fee-guide/spend", spend(spendId, 5000), SUPPORT)).isEqualTo(recorded);
      assertThatThrownBy(() -> opPost(client, CHANNELS + "/fee-guide/spend", spend(spendId, 6000), SUPPORT))
          .isInstanceOf(HttpClientErrorException.Conflict.class);
      assertThatThrownBy(() -> opPost(client, CHANNELS + "/missing/spend", spend(UUID.randomUUID(), 1), SUPPORT))
          .isInstanceOf(HttpClientErrorException.NotFound.class);
      assertThatThrownBy(() -> opPost(client, CHANNELS + "/fee-guide/spend", Map.of("spendId", UUID.randomUUID().toString(),
          "periodStart", "2026-10-31", "periodEnd", "2026-10-01", "amountMinor", 1, "currency", "USD"), SUPPORT))
          .isInstanceOf(HttpClientErrorException.BadRequest.class);
      assertThatThrownBy(() -> opPost(client, CHANNELS + "/fee-guide/spend", Map.of("spendId", UUID.randomUUID().toString(),
          "periodStart", "2026-10-01", "periodEnd", "2026-10-31", "amountMinor", 1, "currency", "EUR"), SUPPORT))
          .isInstanceOf(HttpClientErrorException.BadRequest.class);

      var metrics = opGet(client, CHANNELS + "/metrics", SUPPORT);
      assertThat(metrics.path("primaryMeasure").asText()).isEqualTo("qualifiedUploads");
      var feeGuide = channel(metrics, "fee-guide");
      assertThat(feeGuide.path("qualifiedUploads").asLong()).isEqualTo(1);
      assertThat(feeGuide.at("/qualifiedUploadRate/denominator").asLong()).isEqualTo(2);
      assertThat(feeGuide.at("/shareOfQualifiedUploads/denominator").asLong()).isEqualTo(3);
      assertThat(feeGuide.path("caseStarts").asLong()).isEqualTo(2);
      assertThat(feeGuide.at("/costPerQualifiedUpload/costMinor").asDouble()).isEqualTo(5000.0);
      assertThat(feeGuide.at("/costPerQualifiedUpload/status").asText()).isEqualTo("MEASURED");
      assertThat(feeGuide.at("/costPerPaidCase/status").asText()).isEqualTo("UNAVAILABLE");
      assertThat(feeGuide.at("/spend/firstPeriodStart").asText()).isEqualTo("2026-10-01");
      var inspector = channel(metrics, "inspector");
      assertThat(inspector.path("qualifiedUploads").asLong()).isEqualTo(1);
      assertThat(inspector.at("/costPerQualifiedUpload/reason").asText()).isEqualTo("NO_SPEND_RECORDED");
      var noChannel = channel(metrics, null);
      assertThat(noChannel.path("qualifiedUploads").asLong()).isEqualTo(1);
      assertThat(noChannel.path("caseStarts").asLong()).isEqualTo(8);
      assertThat(channel(metrics, "paused").path("qualifiedUploads").asLong()).isZero();
      assertThat(opGet(client, "/api/v1/operator/pilot/metrics", SUPPORT).at("/acquisitionChannels/channels")).hasSize(4);
      assertThat(metrics.toString()).doesNotContain(referral.at("/case/id").asText(), content.at("/case/id").asText());

      // Deletion removes the channel with the case; only anonymous per-channel counts remain.
      String id = content.at("/case/id").asText(), token = content.path("accessToken").asText();
      post(client, "/api/v1/cases/" + id + "/deletion", Map.of(), "delete-channel-case", token);
      app.context().getBean(com.dealanalyzer.buyingcase.persistence.CaseDeletionWorker.class).runOnce();
      assertThat(count(manager, "select count(*) from analysis_cases where id = '" + id + "'")).isZero();
      var after = channel(opGet(client, CHANNELS + "/metrics", SUPPORT), "fee-guide");
      assertThat(after.path("qualifiedUploads").asLong()).isEqualTo(1);
      assertThat(after.at("/qualifiedUploadRate/denominator").asLong()).isEqualTo(2);
      assertThat(after.path("caseStarts").asLong()).isEqualTo(2);
      assertThat(count(manager, "select removed_qualified_uploads from acquisition_channel_totals where channel_key = 'fee-guide'")).isEqualTo(1);
    }
  }

  @Test void tradeOfferEvidenceAloneIsNotCountedAsAnUpload() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).start("channel-trade-evidence", false, settings())) {
      var client = app.client();
      put(client, CHANNELS + "/fee-guide", Map.of("kind", "EXPLAINER_CONTENT", "active", true), SUPPORT);
      opPost(client, "/api/v1/operator/pilot/freeze", PilotFeedbackAcceptanceTest.protocol(), SUPPORT);
      var created = post(client, "/api/v1/cases", buyingCase("fee-guide"));
      String id = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      opPost(client, "/api/v1/operator/pilot/cases/" + id + "/enrollment", Map.of("qualified", true), SUPPORT);
      TradeVehicleAcceptanceTest.recordTradeWithOffer(app, id, token, "metrics");
      var manager = app.context().getBean(EntityManager.class);
      assertThat(count(manager, "select count(*) from source_documents where case_id = '" + id + "' and purpose = 'TRADE_OFFER_EVIDENCE'"))
          .isEqualTo(1);

      var metrics = opGet(client, "/api/v1/operator/pilot/metrics", SUPPORT);
      assertThat(metrics.at("/uploadToVerifiedExtraction/denominator").asLong()).isZero();
      var feeGuide = channel(metrics.path("acquisitionChannels"), "fee-guide");
      assertThat(feeGuide.path("qualifiedUploads").asLong()).isZero();
      assertThat(feeGuide.at("/qualifiedUploadRate/denominator").asLong()).isEqualTo(1);

      // The anonymous totals kept on deletion count no upload for it either.
      post(client, "/api/v1/cases/" + id + "/deletion", Map.of(), "delete-trade-evidence-case", token);
      app.context().getBean(com.dealanalyzer.buyingcase.persistence.CaseDeletionWorker.class).runOnce();
      assertThat(count(manager, "select count(*) from analysis_cases where id = '" + id + "'")).isZero();
      assertThat(count(manager, "select removed_qualified_count from acquisition_channel_totals where channel_key = 'fee-guide'")).isEqualTo(1);
      assertThat(count(manager, "select removed_qualified_uploads from acquisition_channel_totals where channel_key = 'fee-guide'")).isZero();
      assertThat(channel(opGet(client, CHANNELS + "/metrics", SUPPORT), "fee-guide").path("qualifiedUploads").asLong()).isZero();
    }
  }

  @Test void analysisAndReportAreIdenticalWhateverTheChannel() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).start("channel-isolation", false, settings())) {
      var client = app.client();
      put(client, CHANNELS + "/inspector", Map.of("kind", "REFERRAL_PARTNERSHIP", "referralDisclosure", DISCLOSURE, "active", true), SUPPORT);
      JsonNode attributed = null, plain = null;
      for (var channel : Arrays.asList("inspector", null)) {
        var created = post(client, "/api/v1/cases", buyingCase(channel));
        String base = "/api/v1/cases/" + created.at("/case/id").asText(), token = created.path("accessToken").asText();
        post(client, base + "/analyses", Map.of(), "analysis-" + channel, token);
        var analysis = (ObjectNode) get(client, base + "/reports/1", token).path("analysis").deepCopy();
        analysis.remove("limitations");
        if (channel == null) plain = analysis; else attributed = analysis;
      }
      assertThat(attributed).isEqualTo(plain);
      assertThat(attributed.toString()).doesNotContain("inspector", DISCLOSURE);
    }
  }

  private static Map<String, Object> spend(UUID id, long amountMinor) {
    return Map.of("spendId", id.toString(), "periodStart", "2026-10-01", "periodEnd", "2026-10-31",
        "amountMinor", amountMinor, "currency", "USD");
  }

  private static JsonNode channel(JsonNode metrics, String code) {
    for (var row : metrics.path("channels")) {
      if (code == null ? row.path("channel").isNull() : code.equals(row.path("channel").asText())) return row;
    }
    throw new AssertionError("no channel " + code);
  }

  private static String channelOf(EntityManager manager, String caseId) {
    return (String) manager.createNativeQuery("select acquisition_channel from analysis_cases where id = :id")
        .setParameter("id", caseId).getSingleResult();
  }

  private static long count(EntityManager manager, String sql) {
    return ((Number) manager.createNativeQuery(sql).getSingleResult()).longValue();
  }

  private static JsonNode put(RestClient client, String path, Object body, String token) throws Exception {
    return new ObjectMapper().readTree(client.put().uri(path).header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class));
  }

  private static void upload(RestClient client, JsonNode created) {
    var parts = new LinkedMultiValueMap<String, Object>();
    parts.add("file", new ByteArrayResource(Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")) {
      @Override public String getFilename() { return "dealer-order.png"; }
    });
    client.post().uri("/api/v1/cases/" + created.at("/case/id").asText() + "/documents")
        .contentType(MediaType.MULTIPART_FORM_DATA).header("Idempotency-Key", UUID.randomUUID().toString())
        .header("Case-Access-Token", created.path("accessToken").asText()).body(parts).retrieve().body(String.class);
  }
}

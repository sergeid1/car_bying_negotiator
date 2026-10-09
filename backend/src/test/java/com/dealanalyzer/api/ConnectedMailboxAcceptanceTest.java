package com.dealanalyzer.api;

import static com.dealanalyzer.api.DocumentExtractionAcceptanceTest.*;
import static com.dealanalyzer.api.HttpAcceptanceHarness.*;
import static org.assertj.core.api.Assertions.*;

import com.dealanalyzer.mailbox.FakeMailboxSender;
import com.dealanalyzer.mailbox.MailboxSender.Delivery;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Ticket 83: a signed-in Buyer connects Gmail or Outlook with a send-only permission, and Send from my email sends the
 * exact approved text through it, against the fake adapter. Unclear outcomes wait for the Buyer, refusals leave the
 * own-email-app path open, sends are capped, and disconnecting, revocation and account deletion delete the tokens.
 */
@ExtendWith(OutputCaptureExtension.class)
class ConnectedMailboxAcceptanceTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String VIN = "1HGCM82633A004352";
  private static final String MAILBOX_KEY = "mailbox-test-key-0123456789abcdef0123456789";
  @TempDir Path directory;

  record Result(int status, JsonNode body) {}

  @org.springframework.boot.test.context.TestConfiguration
  @org.springframework.context.annotation.Import({AgreedPriceAcceptanceTest.Configuration.class,
      com.dealanalyzer.mailbox.MailboxLifecycleTestProbe.class})
  static class LifecycleConfiguration {}

  @Test void consentCanFinishAfterSignInWindowAndAnyActiveSessionCanDisconnect() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("mailbox-step-up", false,
        AgreedPriceAcceptanceTest.Configuration.class,
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef",
        "--mailbox.token-encryption-key=" + MAILBOX_KEY)) {
      var client = app.client(); var jdbc = app.context().getBean(JdbcTemplate.class);
      var gmail = app.context().getBean("fakeGmail", FakeMailboxSender.class);
      String session = signIn(client, "step-up@example.com");
      String password = "a long mailbox test phrase";
      assertThat(call(client, "PUT", "/api/v1/account/password", Map.of("password", password), null, session).status()).isEqualTo(200);
      var signed = PasskeyAcceptanceTest.request(client, "POST", "/api/v1/account/password/sign-in",
          Map.of("email", "step-up@example.com", "password", password), null);
      assertThat(signed.status()).isEqualTo(200);
      String passwordSession = signed.cookie().split(";", 2)[0].split("=", 2)[1];
      jdbc.update("update buyer_account_sessions set created_at = ? where sign_in_method = 'EMAIL_LINK'",
          java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(540)));
      var authorization = start(client, session, "GOOGLE");
      // State belongs to the account, not the exact session: password sign-in must not redeem it.
      assertThat(complete(client, passwordSession, authorization.get("state"), authorization.get("code")).status()).isEqualTo(403);
      assertThat(count(jdbc, "mailbox_authorizations")).isEqualTo(1);
      jdbc.update("update buyer_account_sessions set created_at = ?",
          java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(661)));
      assertThat(complete(client, session, authorization.get("state"), authorization.get("code")).status()).isEqualTo(200);
      assertThat(call(client, "POST", "/api/v1/account/mailbox/authorizations", Map.of("provider", "GOOGLE"), null, session).status()).isEqualTo(403);
      assertThat(call(client, "DELETE", "/api/v1/account/mailbox/connection", null, null, passwordSession).status()).isEqualTo(200);
      assertThat(count(jdbc, "mailbox_connections")).isZero();
      assertThat(gmail.withdrawals()).isEqualTo(1);
      assertThat(call(client, "DELETE", "/api/v1/account/mailbox/connection", null, null, session).status()).isEqualTo(200);
    }
  }

  /** No timing guesses: the adapter stops at a boundary until the competing write has committed. */
  private static final class Boundary implements Runnable {
    final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
    final java.util.concurrent.CountDownLatch resume = new java.util.concurrent.CountDownLatch(1);
    @Override public void run() {
      entered.countDown();
      await(resume);
    }
    void reached() { await(entered); }
    void release() { resume.countDown(); }
    private static void await(java.util.concurrent.CountDownLatch latch) {
      try { assertThat(latch.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
      catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }
  }

  @Test void lifecycleFencesExchangeRefreshAndDispatch() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("mailbox-fences", true,
        LifecycleConfiguration.class, "--case.deletion.delay=PT1H",
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef",
        "--mailbox.token-encryption-key=" + MAILBOX_KEY);
        var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var client = app.client();
      var jdbc = app.context().getBean(JdbcTemplate.class);
      var gmail = app.context().getBean("fakeGmail", FakeMailboxSender.class);
      var outlook = app.context().getBean("fakeOutlook", FakeMailboxSender.class);
      String session = signIn(client, "fences@example.com");

      // Disconnect clears unused state, even with no connection.
      var unused = start(client, session, "GOOGLE");
      assertThat(call(client, "DELETE", "/api/v1/account/mailbox/connection", null, null, session).status()).isEqualTo(200);
      assertThat(complete(client, session, unused.get("state"), unused.get("code")).status()).isEqualTo(400);
      assertThat(count(jdbc, "mailbox_authorizations")).isZero();

      // Exchange has obtained a grant, but disconnect / a newer start / deletion wins persistence.
      for (String fence : List.of("disconnect", "supersede", "delete")) {
        var pending = start(client, session, "GOOGLE");
        var exchange = new Boundary();
        gmail.afterExchange(exchange);
        int withdrawals = gmail.withdrawals();
        String activeSession = session;
        var callback = executor.submit(() -> complete(client, activeSession, pending.get("state"), pending.get("code")));
        exchange.reached();
        Map<String, String> newer = null;
        if (fence.equals("supersede")) newer = start(client, session, "MICROSOFT");
        else if (fence.equals("delete"))
          assertThat(call(client, "POST", "/api/v1/account/deletion", Map.of(), null, session).status()).isEqualTo(202);
        else assertThat(call(client, "DELETE", "/api/v1/account/mailbox/connection", null, null, session).status()).isEqualTo(200);
        gmail.withdrawalFails(true);
        exchange.release();
        assertThat(callback.get(30, java.util.concurrent.TimeUnit.SECONDS).status()).isIn(400, 401);
        gmail.afterExchange(() -> {});
        gmail.withdrawalFails(false);
        assertThat(gmail.withdrawals()).isEqualTo(withdrawals + 1);
        assertThat(count(jdbc, "mailbox_connections")).isZero();
        assertThat(count(jdbc, "mailbox_authorizations")).isEqualTo(newer == null ? 0 : 1);
        if (newer != null) {
          assertThat(complete(client, session, newer.get("state"), newer.get("code")).status()).isEqualTo(200);
          call(client, "DELETE", "/api/v1/account/mailbox/connection", null, null, session);
        }
      }

      var created = post(client, "/api/v1/cases", validCase(), "fenced-case");
      String caseId = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      String base = "/api/v1/cases/" + caseId;
      String dealer = negotiableCase(app, base, caseId, token);
      session = TestDriveAcceptanceTest.signInAndAttach(client, caseId, token);
      // Replacements withdraw through the previous provider in both directions.
      String previousProvider = null;
      for (String provider : List.of("GOOGLE", "MICROSOFT", "GOOGLE")) {
        int g = gmail.withdrawals(), m = outlook.withdrawals();
        var auth = start(client, session, provider);
        assertThat(complete(client, session, auth.get("state"), auth.get("code")).status()).isEqualTo(200);
        assertThat(gmail.withdrawals()).isEqualTo(g + ("GOOGLE".equals(previousProvider) ? 1 : 0));
        assertThat(outlook.withdrawals()).isEqualTo(m + ("MICROSOFT".equals(previousProvider) ? 1 : 0));
        previousProvider = provider;
      }

      for (String fence : List.of("disconnect", "prepared", "replacement", "mandate", "delete")) {
        if (fence.equals("delete")) approveMandate(client, base, token, "after-revocation");
        if (!fence.equals("disconnect")) {
          var auth = start(client, session, "GOOGLE");
          assertThat(complete(client, session, auth.get("state"), auth.get("code")).status()).isEqualTo(200);
        }
        String message = handOff(client, base, token, dealer, "sales@dealer.example", "race-" + fence);
        jdbc.update("update mailbox_connections set access_expires_at = current_timestamp");
        var refresh = new Boundary();
        var probe = app.context().getBean(com.dealanalyzer.mailbox.MailboxLifecycleTestProbe.class);
        if (List.of("prepared", "replacement", "delete").contains(fence)) probe.afterPreparation(refresh);
        else gmail.afterRefresh(refresh);
        String activeSession = session;
        var sending = executor.submit(() -> send(client, base, token, activeSession, message));
        refresh.reached();
        switch (fence) {
          case "mandate" -> post(client, base + "/mandates/" + latestMandate(client, base, token) + "/revocation", Map.of(), "race-revoke", token);
          case "replacement" -> {
            var auth = start(client, session, "MICROSOFT");
            assertThat(complete(client, session, auth.get("state"), auth.get("code")).status()).isEqualTo(200);
          }
          case "delete" -> call(client, "POST", "/api/v1/account/deletion", Map.of(), null, session);
          default -> {
            gmail.withdrawalFails(true);
            assertThat(call(client, "DELETE", "/api/v1/account/mailbox/connection", null, null, session).status()).isEqualTo(200);
            gmail.withdrawalFails(false);
          }
        }
        refresh.release();
        assertThat(sending.get(30, java.util.concurrent.TimeUnit.SECONDS).status()).isEqualTo(fence.equals("delete") ? 401 : 409);
        gmail.afterRefresh(() -> {});
        probe.afterPreparation(() -> {});
        assertThat(gmail.sent()).isEmpty();
        assertThat(jdbc.queryForObject("select state from dealer_relay_messages where id = ?", String.class, message)).as(fence).isEqualTo("HANDED_OFF");
        assertThat(jdbc.queryForObject("select outcome from mailbox_sends where message_id = ?", String.class, message)).isEqualTo("UNAVAILABLE");
        assertThat(jdbc.queryForObject("select count(*) from audit_events where case_id = ? and event_type = 'DEALER_MESSAGE_MAILBOX_NOT_SENT'", Long.class, caseId)).isPositive();
      }
    }
  }

  @Test void concurrentExpiredAndLateAttemptsAreFenced() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("mailbox-concurrency", true,
        LifecycleConfiguration.class, "--case.deletion.delay=PT1H",
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef",
        "--mailbox.token-encryption-key=" + MAILBOX_KEY);
        var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var client = app.client();
      var jdbc = app.context().getBean(JdbcTemplate.class);
      var gmail = app.context().getBean("fakeGmail", FakeMailboxSender.class);
      var probe = app.context().getBean(com.dealanalyzer.mailbox.MailboxLifecycleTestProbe.class);
      var created = post(client, "/api/v1/cases", validCase(), "concurrent-case");
      String caseId = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      String base = "/api/v1/cases/" + caseId;
      String dealer = negotiableCase(app, base, caseId, token);
      String session = TestDriveAcceptanceTest.signInAndAttach(client, caseId, token);
      var auth = start(client, session, "GOOGLE");
      assertThat(complete(client, session, auth.get("state"), auth.get("code")).status()).isEqualTo(200);

      for (String phase : List.of("prepared", "late-success", "late-refusal")) {
        String message = handOff(client, base, token, dealer, "sales@dealer.example", phase);
        var boundary = new Boundary();
        probe.resetAttempt();
        probe.afterPreparation(boundary);
        var first = executor.submit(() -> send(client, base, token, session, message));
        boundary.reached();
        try {
          assertThat(send(client, base, token, session, message).body().path("code").asText()).isEqualTo("SEND_IN_PROGRESS");
          // Advance the persisted attempt, not wall-clock time. This also represents a process lost after claim.
          jdbc.update("update mailbox_sends set started_at = ? where message_id = ?",
              java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(600)), message);
          probe.afterPreparation(() -> {});
          assertThat(send(client, base, token, session, message).body().path("state").asText()).isEqualTo("SENT");
        } finally { boundary.release(); }
        var old = first.get(30, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(old.status()).isEqualTo(409);
        if (!phase.equals("prepared")) probe.staleCompletion(phase.equals("late-success"));
        assertThat(handoff(client, base, token, message).path("state").asText()).isEqualTo("SENT");
        assertThat(jdbc.queryForList("select outcome from mailbox_sends where message_id = ? order by started_at", String.class, message))
            .containsExactly("DELIVERY_UNKNOWN", "SENT");
        assertThat(jdbc.queryForObject("select count(*) from audit_events where case_id = ? and event_type = 'DEALER_MESSAGE_SEND_AGAIN'",
            Long.class, caseId)).isEqualTo(phase.equals("prepared") ? 1 : phase.equals("late-success") ? 2 : 3);
      }
      // The superseded prepared attempt never dispatched; an explicitly repeated UNKNOWN send can duplicate.
      assertThat(gmail.sent()).hasSize(3);

      String slow = handOff(client, base, token, dealer, "sales@dealer.example", "slow-send");
      var dispatch = new Boundary(); gmail.atSend(dispatch);
      var sending = executor.submit(() -> send(client, base, token, session, slow));
      dispatch.reached();
      try {
        jdbc.update("update mailbox_sends set started_at = ? where message_id = ?",
            java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(600)), slow);
        var competitorStarted = new java.util.concurrent.CountDownLatch(1);
        var competitor = executor.submit(() -> { competitorStarted.countDown(); return send(client, base, token, session, slow); });
        Boundary.await(competitorStarted);
        dispatch.release();
        assertThat(sending.get(30, java.util.concurrent.TimeUnit.SECONDS).body().path("state").asText()).isEqualTo("SENT");
        assertThat(competitor.get(30, java.util.concurrent.TimeUnit.SECONDS).status()).isEqualTo(409);
        assertThat(gmail.sent()).hasSize(4);
        assertThat(jdbc.queryForObject("select count(*) from mailbox_sends where message_id = ?", Long.class, slow)).isEqualTo(1);
      } finally { dispatch.release(); gmail.atSend(() -> {}); }
    }
  }

  @Test void appointmentReleaseIsRecheckedAndDispatchedSendCannotBeRecalled() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("mailbox-release", true,
        LifecycleConfiguration.class, "--case.deletion.delay=PT1H",
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef",
        "--mailbox.token-encryption-key=" + MAILBOX_KEY);
        var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var client = app.client();
      var jdbc = app.context().getBean(JdbcTemplate.class);
      var gmail = app.context().getBean("fakeGmail", FakeMailboxSender.class);
      var created = post(client, "/api/v1/cases", validCase(), "appointment-case");
      String caseId = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      String base = "/api/v1/cases/" + caseId, path = base + "/appointment";
      TestDriveAcceptanceTest.agree(app, client, base, caseId, token);
      String session = TestDriveAcceptanceTest.signInAndAttach(client, caseId, token);
      var auth = start(client, session, "GOOGLE");
      assertThat(complete(client, session, auth.get("state"), auth.get("code")).status()).isEqualTo(200);
      var day = java.time.LocalDate.now().plusDays(3);
      var proposal = post(client, path + "/proposals", Map.of("timeZone", "America/Chicago", "windows",
          List.of(Map.of("start", day + "T10:00:00", "end", day + "T13:00:00"))), "proposal", token);
      var handed = post(client, path + "/messages/" + proposal.path("id").asText() + "/approval",
          Map.of("approved", true, "text", proposal.at("/gatedDraft/draft/text").asText()), "proposal-approve", token);
      DealerHandoffTestSupport.markSent(client, base, token, handed);
      var relay = app.context().getBean(com.dealanalyzer.relay.RelayService.class);
      var reply = relay.receive(new com.dealanalyzer.relay.DealerMailRelay.InboundMail("release-reply",
          get(client, base + "/relay", token).path("address").asText(), "sales@dealer.example", "Test drive", "Option 1 works.", List.of()));
      var booking = TestDriveAcceptanceTest.postWithSession(client, path + "/bookings",
          Map.of("proposalId", proposal.path("id").asText(), "messageId", reply.messageId(), "option", 1,
              "release", List.of("FIRST_NAME", "EMAIL")), "booking", token, session);
      var book = TestDriveAcceptanceTest.postWithSession(client, path + "/messages/" + booking.path("id").asText() + "/approval",
          Map.of("approved", true, "text", booking.at("/gatedDraft/draft/text").asText()), "booking-approve", token, session);
      String message = book.path("messageId").asText();
      var prepared = new Boundary();
      var probe = app.context().getBean(com.dealanalyzer.mailbox.MailboxLifecycleTestProbe.class);
      probe.afterPreparation(prepared);
      var sending = executor.submit(() -> send(client, base, token, session, message));
      prepared.reached();
      // Controlled release withdrawal fixture; no production endpoint permits editing frozen message text.
      jdbc.update("update test_drive_contact_releases set state = 'WITHHELD' where case_id = ?", caseId);
      prepared.release();
      var refused = sending.get(30, java.util.concurrent.TimeUnit.SECONDS);
      assertThat(refused.status()).isEqualTo(409);
      assertThat(refused.body().path("detail").asText()).contains("no longer released").doesNotContain("Rosalind", TestDriveAcceptanceTest.EMAIL);
      assertThat(gmail.sent()).isEmpty();
      assertThat(handoff(client, base, token, message).path("state").asText()).isEqualTo("HANDED_OFF");
      probe.afterPreparation(() -> {});

      // Once the provider is entered, disconnect queues behind dispatch and cannot recall this call.
      jdbc.update("update test_drive_contact_releases set state = 'PREPARED' where case_id = ?", caseId);
      gmail.atSend(() -> { throw new IllegalStateException("private-provider-detail"); });
      var uncertain = send(client, base, token, session, message);
      assertThat(uncertain.status()).isEqualTo(200);
      assertThat(uncertain.body().path("state").asText()).isEqualTo("DELIVERY_UNKNOWN");
      assertThat(uncertain.body().toString()).doesNotContain("private-provider-detail", "fake-access", "fake-refresh");
      assertThat(jdbc.queryForObject("select outcome from mailbox_sends where message_id = ? order by started_at desc limit 1", String.class, message))
          .isEqualTo("DELIVERY_UNKNOWN");
      assertThat(gmail.sent()).isEmpty();
      var dispatch = new Boundary();
      gmail.atSend(dispatch);
      sending = executor.submit(() -> send(client, base, token, session, message));
      dispatch.reached();
      var disconnectStarted = new java.util.concurrent.CountDownLatch(1);
      var disconnect = executor.submit(() -> {
        disconnectStarted.countDown();
        return call(client, "DELETE", "/api/v1/account/mailbox/connection", null, null, session);
      });
      Boundary.await(disconnectStarted);
      dispatch.release();
      assertThat(sending.get(30, java.util.concurrent.TimeUnit.SECONDS).body().path("state").asText()).isEqualTo("SENT");
      assertThat(disconnect.get(30, java.util.concurrent.TimeUnit.SECONDS).status()).isEqualTo(200);
      assertThat(gmail.sent()).hasSize(1);
      assertThat(count(jdbc, "mailbox_connections")).isZero();
      assertThat(count(jdbc, "mailbox_authorizations")).isZero();
      assertThat(send(client, base, token, session, message).status()).isEqualTo(409);
      assertThat(gmail.sent()).hasSize(1);
    }
  }

  @Test void restartPreservesCrashedClaimWithoutDispatchingOrAutomaticallyRetrying() throws Exception {
    var harness = new HttpAcceptanceHarness(directory);
    String[] properties = {"--case.deletion.delay=PT1H",
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef", "--mailbox.token-encryption-key=" + MAILBOX_KEY};
    String caseId, token, session, message, base;
    try (var app = harness.startWithConfiguration("mailbox-restart", true, LifecycleConfiguration.class, properties)) {
      var client = app.client();
      var created = post(client, "/api/v1/cases", validCase(), "restart-case");
      caseId = created.at("/case/id").asText(); token = created.path("accessToken").asText();
      base = "/api/v1/cases/" + caseId;
      String dealer = negotiableCase(app, base, caseId, token);
      session = TestDriveAcceptanceTest.signInAndAttach(client, caseId, token);
      var auth = start(client, session, "GOOGLE");
      assertThat(complete(client, session, auth.get("state"), auth.get("code")).status()).isEqualTo(200);
      message = handOff(client, base, token, dealer, "sales@dealer.example", "crash-claim");
      var probe = app.context().getBean(com.dealanalyzer.mailbox.MailboxLifecycleTestProbe.class);
      // Abrupt termination at this boundary bypasses the service's normal failure/completion handling.
      probe.afterPreparation(() -> { throw new AssertionError("simulated process loss after committed claim"); });
      assertThatThrownBy(() -> app.context().getBean(com.dealanalyzer.mailbox.MailboxSendService.class)
          .send(caseId, token, session, message)).isInstanceOf(AssertionError.class);
      assertThat(app.context().getBean("fakeGmail", FakeMailboxSender.class).sent()).isEmpty();
      assertThat(app.context().getBean(JdbcTemplate.class).queryForObject(
          "select outcome from mailbox_sends where message_id = ?", String.class, message)).isEqualTo("SENDING");
    }
    try (var app = harness.startWithConfiguration("mailbox-restart", true, LifecycleConfiguration.class, properties)) {
      var client = app.client(); var jdbc = app.context().getBean(JdbcTemplate.class);
      var gmail = app.context().getBean("fakeGmail", FakeMailboxSender.class);
      assertThat(gmail.sent()).isEmpty();
      assertThat(handoff(client, base, token, message).path("state").asText()).isEqualTo("DELIVERY_UNKNOWN");
      assertThat(send(client, base, token, session, message).body().path("code").asText()).isEqualTo("SEND_IN_PROGRESS");
      jdbc.update("update mailbox_sends set started_at = ? where message_id = ?",
          java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(600)), message);
      assertThat(gmail.sent()).isEmpty();
      assertThat(send(client, base, token, session, message).body().path("state").asText()).isEqualTo("SENT");
      assertThat(gmail.sent()).hasSize(1);
      assertThat(jdbc.queryForList("select outcome from mailbox_sends where message_id = ? order by started_at", String.class, message))
          .containsExactly("DELIVERY_UNKNOWN", "SENT");
      assertThat(jdbc.queryForObject("select count(*) from audit_events where case_id = ? and event_type = 'DEALER_MESSAGE_SEND_AGAIN'",
          Long.class, caseId)).isEqualTo(1);
    }
  }

  @Test void connectSendCheckUnclearOutcomesAndDisconnect(CapturedOutput output) throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("connected-mailbox", true,
        AgreedPriceAcceptanceTest.Configuration.class, "--case.deletion.delay=PT1H",
        "--document.storage.path=" + directory.resolve("objects"),
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef",
        "--mailbox.token-encryption-key=" + MAILBOX_KEY, "--mailbox.caps.per-dealer=5")) {
      var client = app.client();
      var jdbc = app.context().getBean(JdbcTemplate.class);
      var gmail = app.context().getBean("fakeGmail", FakeMailboxSender.class);
      var outlook = app.context().getBean("fakeOutlook", FakeMailboxSender.class);
      var created = post(client, "/api/v1/cases", validCase(), "case");
      String caseId = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      String base = "/api/v1/cases/" + caseId;
      String dealer = negotiableCase(app, base, caseId, token);
      String session = TestDriveAcceptanceTest.signInAndAttach(client, caseId, token);

      // Without a connection, approving offers only the own-email-app path, and a mailbox send is refused with that offer.
      var mailbox = call(client, "GET", "/api/v1/account/mailbox", null, null, session).body();
      assertThat(mailbox.path("available").asBoolean()).isTrue();
      assertThat(mailbox.path("providers").toString()).contains("GOOGLE", "MICROSOFT");
      assertThat(mailbox.path("connection").isNull()).isTrue();
      String first = handOff(client, base, token, dealer, "sales@dealer.example", "q1");
      var unconnected = send(client, base, token, session, first);
      assertThat(unconnected.status()).isEqualTo(409);
      assertThat(unconnected.body().path("code").asText()).isEqualTo("MAILBOX_NOT_CONNECTED");
      assertThat(unconnected.body().path("ownEmailApp").asBoolean()).isTrue();
      assertThat(handoff(client, base, token, first).path("state").asText()).isEqualTo("HANDED_OFF");

      // Connecting checks the single-use state, the PKCE verifier and the granted scopes; a read scope is refused.
      var expiredAuthorization = start(client, session, "GOOGLE");
      jdbc.update("update mailbox_authorizations set expires_at = ?", java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)));
      assertThat(complete(client, session, expiredAuthorization.get("state"), expiredAuthorization.get("code"))
          .body().path("code").asText()).isEqualTo("AUTHORIZATION_UNKNOWN");
      assertThat(count(jdbc, "mailbox_authorizations")).isZero();
      var authorization = start(client, session, "GOOGLE");
      assertThat(call(client, "POST", "/api/v1/account/mailbox/connection",
          Map.of("state", "forged-state", "code", authorization.get("code")), null, session).body().path("code").asText())
          .isEqualTo("AUTHORIZATION_UNKNOWN");
      var readScope = start(client, session, "GOOGLE");
      var refused = complete(client, session, readScope.get("state"), readScope.get("code") + ".read");
      assertThat(refused.status()).isEqualTo(400);
      assertThat(refused.body().path("code").asText()).isEqualTo("READ_SCOPE_REFUSED");
      assertThat(count(jdbc, "mailbox_connections")).isZero();
      authorization = start(client, session, "GOOGLE");
      String someoneElse = signIn(client, "someone-else@example.com");
      assertThat(complete(client, someoneElse, authorization.get("state"), authorization.get("code")).body().path("code").asText())
          .isEqualTo("AUTHORIZATION_UNKNOWN");
      var connected = complete(client, session, authorization.get("state"), authorization.get("code"));
      assertThat(connected.status()).isEqualTo(200);
      assertThat(connected.body().at("/connection/provider").asText()).isEqualTo("GOOGLE");
      assertThat(connected.body().at("/connection/address").asText()).isEqualTo(FakeMailboxSender.ADDRESS);
      assertThat(connected.body().toString()).doesNotContain("fake-access", "fake-refresh", "Token");
      assertThat(complete(client, session, authorization.get("state"), authorization.get("code")).body().path("code").asText())
          .isEqualTo("AUTHORIZATION_UNKNOWN");
      String stored = jdbc.queryForList("select * from mailbox_connections").toString();
      assertThat(stored).doesNotContain("fake-access", "fake-refresh", FakeMailboxSender.ADDRESS).contains("gmail.send");
      assertThat(stored).doesNotContain("readonly");

      // Send from my email: the exact approved text from the Buyer's address, recorded once as CONNECTED_MAILBOX.
      String text = handoff(client, base, token, first).path("body").asText();
      var sent = send(client, base, token, session, first);
      assertThat(sent.status()).isEqualTo(200);
      assertThat(sent.body().path("state").asText()).isEqualTo("SENT");
      assertThat(sent.body().path("sendPath").asText()).isEqualTo("CONNECTED_MAILBOX");
      assertThat(gmail.sent()).singleElement().satisfies(message -> {
        assertThat(message.from()).isEqualTo(FakeMailboxSender.ADDRESS);
        assertThat(message.message().to()).isEqualTo("sales@dealer.example");
        assertThat(message.message().subject()).isEqualTo("Request for an itemized vehicle quote");
        assertThat(message.message().body()).isEqualTo(text);
      });
      assertThat(get(client, base + "/quote-requests", token).path("negotiationState").asText()).isEqualTo("AWAITING_DEALER");
      assertThat(jdbc.queryForObject("select state from quote_requests where message_id = ?", String.class, first)).isEqualTo("SENT");
      assertThat(send(client, base, token, session, first).status()).isEqualTo(409);
      assertThat(call(client, "POST", base + "/handoffs/" + first + "/sent", Map.of(), token, null).status()).isEqualTo(409);
      assertThat(gmail.sent()).hasSize(1);
      assertThat(find(get(client, base + "/negotiation-conversation", token).path("entries"), "kind", "OUTBOUND_MESSAGE")
          .at("/message/sendPath").asText()).isEqualTo("CONNECTED_MAILBOX");

      // An unclear outcome is never retried: the Buyer checks their Sent folder and says it was sent.
      String second = handOff(client, base, token, dealer, "sales@dealer.example", "q2");
      gmail.nextOutcomes(Delivery.UNKNOWN);
      var unclear = send(client, base, token, session, second);
      assertThat(unclear.body().path("state").asText()).isEqualTo("DELIVERY_UNKNOWN");
      assertThat(gmail.sent()).hasSize(2);
      assertThat(jdbc.queryForObject("select state from quote_requests where message_id = ?", String.class, second)).isEqualTo("HANDED_OFF");
      assertThat(call(client, "POST", base + "/handoffs/" + second + "/discard", Map.of(), token, null).body().path("detail").asText())
          .contains("Sent folder");
      var confirmed = call(client, "POST", base + "/handoffs/" + second + "/sent", Map.of(), token, null);
      assertThat(confirmed.body().path("state").asText()).isEqualTo("SENT");
      assertThat(confirmed.body().path("sendPath").asText()).isEqualTo("CONNECTED_MAILBOX");
      assertThat(jdbc.queryForObject("select state from quote_requests where message_id = ?", String.class, second)).isEqualTo("SENT");
      assertThat(gmail.sent()).hasSize(2);

      // Or the Buyer sends it again: the same stored text, audited.
      String third = handOff(client, base, token, dealer, "sales@dealer.example", "q3");
      gmail.nextOutcomes(Delivery.UNKNOWN);
      assertThat(send(client, base, token, session, third).body().path("state").asText()).isEqualTo("DELIVERY_UNKNOWN");
      var again = send(client, base, token, session, third);
      assertThat(again.body().path("state").asText()).isEqualTo("SENT");
      assertThat(gmail.sent()).hasSize(4);
      assertThat(gmail.sent().get(3).message()).usingRecursiveComparison().isEqualTo(gmail.sent().get(2).message());
      assertThat(jdbc.queryForObject("select count(*) from audit_events where aggregate_id = ? and event_type = 'DEALER_MESSAGE_SEND_AGAIN'",
          Long.class, third)).isEqualTo(1);
      assertThat(jdbc.queryForObject("select state from quote_requests where message_id = ?", String.class, third)).isEqualTo("SENT");

      // A provider rate limit is stated plainly and not retried; the per-dealer cap stops the next send.
      String fourth = handOff(client, base, token, dealer, "sales@dealer.example", "q4");
      gmail.nextOutcomes(Delivery.RATE_LIMITED);
      var limited = send(client, base, token, session, fourth);
      assertThat(limited.status()).isEqualTo(429);
      assertThat(limited.body().path("code").asText()).isEqualTo("PROVIDER_RATE_LIMITED");
      assertThat(limited.body().path("detail").asText()).contains("Gmail says too many messages", "nothing was sent");
      assertThat(handoff(client, base, token, fourth).path("state").asText()).isEqualTo("HANDED_OFF");
      assertThat(handoff(client, base, token, fourth).path("sendPath").asText()).isEqualTo("OWN_EMAIL_APP");
      assertThat(gmail.sent()).hasSize(4);
      assertThat(send(client, base, token, session, fourth).body().path("state").asText()).isEqualTo("SENT");
      String capped = handOff(client, base, token, dealer, "sales@dealer.example", "q4b");
      var refusedByCap = send(client, base, token, session, capped);
      assertThat(refusedByCap.status()).isEqualTo(429);
      assertThat(refusedByCap.body().path("code").asText()).isEqualTo("MAILBOX_CAP_REACHED");
      assertThat(refusedByCap.body().path("detail").asText()).contains("5 messages to this dealer");
      assertThat(gmail.sent()).hasSize(5);
      assertThat(jdbc.queryForObject("select count(*) from mailbox_sends where case_id = ?", Long.class, caseId)).isEqualTo(6);
      assertThat(call(client, "POST", base + "/handoffs/" + capped + "/sent", Map.of(), token, null).body().path("sendPath").asText())
          .isEqualTo("OWN_EMAIL_APP");

      // Revoking the mandate blocks a Connected-mailbox send at once.
      String fifth = handOff(client, base, token, dealer, "fleet@dealer.example", "q5");
      post(client, base + "/mandates/" + latestMandate(client, base, token) + "/revocation", Map.of(), "revoke", token);
      var revoked = send(client, base, token, session, fifth);
      assertThat(revoked.status()).isEqualTo(409);
      assertThat(revoked.body().path("detail").asText()).contains("It was not sent");
      assertThat(gmail.sent()).hasSize(5);
      approveMandate(client, base, token, "mandate2");

      // Disconnecting deletes the tokens at once; a later send is refused with the own-email-app offer.
      String sixth = handOff(client, base, token, dealer, "fleet@dealer.example", "q6");
      var disconnected = call(client, "DELETE", "/api/v1/account/mailbox/connection", null, null, session);
      assertThat(disconnected.body().path("connection").isNull()).isTrue();
      assertThat(count(jdbc, "mailbox_connections")).isZero();
      var afterDisconnect = send(client, base, token, session, sixth);
      assertThat(afterDisconnect.body().path("code").asText()).isEqualTo("MAILBOX_NOT_CONNECTED");
      assertThat(afterDisconnect.body().path("detail").asText()).contains("own email app");

      // A provider revocation error deletes the connection and nothing is sent.
      var outlookAuthorization = start(client, session, "MICROSOFT");
      assertThat(complete(client, session, outlookAuthorization.get("state"), outlookAuthorization.get("code")).body()
          .at("/connection/providerLabel").asText()).isEqualTo("Outlook");
      jdbc.update("update mailbox_connections set access_expires_at = ?", java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(5)));
      outlook.withdrawGrant();
      var withdrawn = send(client, base, token, session, sixth);
      assertThat(withdrawn.body().path("code").asText()).isEqualTo("MAILBOX_REVOKED");
      assertThat(count(jdbc, "mailbox_connections")).isZero();
      assertThat(outlook.sent()).isEmpty();
      assertThat(handoff(client, base, token, sixth).path("state").asText()).isEqualTo("HANDED_OFF");

      // Account export has the provider and address, never tokens; account deletion removes the connection at once.
      var reconnect = start(client, session, "GOOGLE");
      complete(client, session, reconnect.get("state"), reconnect.get("code"));
      byte[] export = client.post().uri("/api/v1/account/export").header("Cookie", AccountSessionTestSupport.cookie(session))
          .header("Account-Request", "1").retrieve().body(byte[].class);
      String account = zipEntry(export, "account.json");
      assertThat(JSON.readTree(account).at("/mailboxConnections/0/provider").asText()).isEqualTo("GOOGLE");
      assertThat(JSON.readTree(account).at("/mailboxConnections/0/address").asText()).isEqualTo(FakeMailboxSender.ADDRESS);
      assertThat(JSON.readTree(account).path("mailboxSends").size()).isEqualTo(7);
      assertThat(account).doesNotContain("fake-access", "fake-refresh", "ciphertext", "sales@dealer.example");
      call(client, "POST", "/api/v1/account/deletion", Map.of(), null, session);
      assertThat(count(jdbc, "mailbox_connections")).isZero();
      assertThat(count(jdbc, "mailbox_authorizations")).isZero();

      // Nothing about the connection or the messages reached the logs.
      assertThat(output.getAll()).doesNotContain("fake-access", "fake-refresh", FakeMailboxSender.ADDRESS, text, MAILBOX_KEY);
    }
  }

  /** A case whose negotiation is ready for quote requests; returns the dealer. */
  private static String negotiableCase(HttpAcceptanceHarness.RunningApplication app, String base, String caseId, String token)
      throws Exception {
    var jdbc = app.context().getBean(JdbcTemplate.class);
    OutboundDraftAcceptanceTest.verifyAndPlan(app, base, token);
    jdbc.update("update vehicles set vin = ? where case_id = ?", VIN, caseId);
    String dealer = UUID.randomUUID().toString();
    jdbc.update("insert into dealers(id, case_id, source, provider_dealer_id, name, created_at) values (?, ?, 'TEST', 'dealer', 'North Branch Motors', current_timestamp)", dealer, caseId);
    jdbc.update("""
        insert into listing_snapshots(id, case_id, dealer_id, provider_listing_id, listing_url, vin, source, retrieved_at, provider_version, license_metadata, created_at)
        values (?, ?, ?, 'listing', 'https://dealer.example/vehicle/42', ?, 'TEST', current_timestamp, 'v1', 'test', current_timestamp)
        """, UUID.randomUUID().toString(), caseId, dealer, VIN);
    approveMandate(app.client(), base, token, "mandate1");
    return dealer;
  }

  /** Prepares and approves a quote request; returns the handed-off message. */
  private static String handOff(RestClient client, String base, String token, String dealer, String address, String key) throws Exception {
    var quote = post(client, base + "/quote-requests", Map.of("dealerId", dealer, "dealerAddress", address), key, token);
    return post(client, base + "/quote-requests/" + quote.path("id").asText() + "/approval",
        Map.of("approved", true, "text", quote.at("/gatedDraft/draft/text").asText()), key + "-approve", token).path("messageId").asText();
  }

  private static JsonNode handoff(RestClient client, String base, String token, String messageId) throws Exception {
    return get(client, base + "/handoffs/" + messageId, token);
  }

  private static Result send(RestClient client, String base, String token, String session, String messageId) {
    return call(client, "POST", base + "/handoffs/" + messageId + "/mailbox-send", Map.of(), token, session);
  }

  /** Starts connecting and follows the fake consent page back: the code and state the redirect carries. */
  private static Map<String, String> start(RestClient client, String session, String provider) {
    var started = call(client, "POST", "/api/v1/account/mailbox/authorizations", Map.of("provider", provider), null, session);
    assertThat(started.status()).isEqualTo(200);
    var query = UriComponentsBuilder.fromUri(URI.create(started.body().path("authorizationUrl").asText())).build().getQueryParams();
    return Map.of("code", decode(query.getFirst("code")), "state", decode(query.getFirst("state")));
  }

  private static Result complete(RestClient client, String session, String state, String code) {
    return call(client, "POST", "/api/v1/account/mailbox/connection", Map.of("state", state, "code", code), null, session);
  }

  private static String decode(String value) { return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8); }

  private static Result call(RestClient client, String method, String path, Object body, String token, String session) {
    var request = client.method(HttpMethod.valueOf(method)).uri(path).header("Idempotency-Key", UUID.randomUUID().toString());
    if (token != null) request.header("Case-Access-Token", token);
    if (session != null) request.header("Cookie", AccountSessionTestSupport.cookie(session));
    if (!method.equals("GET")) request.header("Account-Request", "1");
    if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
    return request.exchange((ignored, response) -> {
      byte[] bytes = response.getBody().readAllBytes();
      return new Result(response.getStatusCode().value(), bytes.length == 0 ? JSON.nullNode() : JSON.readTree(bytes));
    });
  }

  private static String signIn(RestClient client, String email) throws Exception {
    post(client, "/api/v1/account/sign-in-requests", Map.of("email", email), "sign-in-" + email);
    return AccountSessionTestSupport.verify(client, BuyerAccountAcceptanceTest.sent.getLast().link().split("#sign-in=")[1]);
  }

  private static long count(JdbcTemplate jdbc, String table) {
    return jdbc.queryForObject("select count(*) from " + table, Long.class);
  }

  private static int latestMandate(RestClient client, String base, String token) throws Exception {
    var versions = new ArrayList<Integer>();
    get(client, base + "/mandates", token).findValues("version").forEach(value -> versions.add(value.asInt()));
    return versions.stream().max(Integer::compare).orElseThrow();
  }

  private static void approveMandate(RestClient client, String base, String token, String key) throws Exception {
    var proposal = get(client, base + "/mandates/proposal", token);
    post(client, base + "/mandates", Map.of("planVersion", proposal.path("planVersion").asInt(), "targetMinor", proposal.path("targetMinor").asLong(),
        "ceilingMinor", proposal.path("ceilingMinor").asLong(), "approved", true), key, token);
  }

  private static String zipEntry(byte[] bytes, String name) throws Exception {
    try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
      for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry())
        if (entry.getName().equals(name)) return new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
    throw new AssertionError("Missing archive entry: " + name);
  }
}

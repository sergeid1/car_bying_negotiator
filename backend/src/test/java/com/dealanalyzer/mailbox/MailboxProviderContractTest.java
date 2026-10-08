package com.dealanalyzer.mailbox;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The real adapters, with endpoint overrides confined to tests and no external network. */
class MailboxProviderContractTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String CLIENT = "registered-client";
  private static final String VERIFIER = "v".repeat(43);
  private static final String REDIRECT = "https://buyer.example/callback?registered=value";
  private static final String TENANT = "9188040d-6c67-4c5b-b112-36a304b66dad";

  private static final class Stub implements AutoCloseable {
    final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    final AtomicInteger calls = new AtomicInteger();
    volatile int status = 200;
    volatile String reply = "{}", request, authorization, contentType;
    volatile boolean drop;
    volatile Map<String, String> registeredExchange;
    volatile CountDownLatch entered, release;
    Stub() throws Exception {
      server.setExecutor(executor);
      server.createContext("/", exchange -> {
        calls.incrementAndGet();
        request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        authorization = exchange.getRequestHeaders().getFirst("Authorization");
        contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (entered != null) entered.countDown();
        if (release != null) {
          try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("stub was not released"); }
          catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        try {
          if (!drop) {
            boolean refused = registeredExchange != null && !form(request).entrySet().containsAll(registeredExchange.entrySet());
            byte[] body = (refused ? "{\"error\":\"invalid_grant\"}" : reply).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(refused ? 400 : status, body.length);
            exchange.getResponseBody().write(body);
          }
        } finally { exchange.close(); }
      });
      server.start();
    }
    String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/"; }
    OAuthMailboxSender sender(MailboxProvider provider, Duration timeout) {
      var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
      return provider == MailboxProvider.GOOGLE
          ? new GmailMailboxSender(CLIENT, "client-secret", http, timeout) {
            @Override String tokenEndpoint() { return endpoint(); }
            @Override String sendEndpoint() { return endpoint(); }
          }
          : new GraphMailboxSender(CLIENT, "client-secret", http, timeout) {
            @Override String tokenEndpoint() { return endpoint(); }
            @Override String sendEndpoint() { return endpoint(); }
          };
    }
    @Override public void close() {
      if (release != null) release.countDown();
      server.stop(0); executor.close();
    }
  }

  private static Map<String, Object> claims(MailboxProvider provider) {
    var claims = new LinkedHashMap<String, Object>();
    claims.put("iss", provider == MailboxProvider.GOOGLE ? "https://accounts.google.com"
        : "https://login.microsoftonline.com/" + TENANT + "/v2.0");
    claims.put("aud", CLIENT); claims.put("sub", "provider-subject");
    claims.put("exp", Instant.now().plusSeconds(600).getEpochSecond());
    claims.put("nonce", Pkce.challenge(VERIFIER));
    claims.put("email", "Buyer@example.com"); claims.put("email_verified", true);
    if (provider == MailboxProvider.MICROSOFT) { claims.put("tid", TENANT); claims.put("ver", "2.0"); }
    return claims;
  }

  private static String jwt(Map<String, Object> claims) throws Exception {
    return Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8))
        + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(claims)) + ".c2lnbmF0dXJl";
  }
  private static Map<String, Object> token(MailboxProvider provider) throws Exception {
    return new LinkedHashMap<>(Map.of("access_token", "access-secret", "refresh_token", "refresh-secret",
        "token_type", "Bearer", "expires_in", 3600, "scope", provider.requestedScope(), "id_token", jwt(claims(provider))));
  }
  private static Map<String, String> form(String text) {
    var result = new LinkedHashMap<String, String>();
    for (String field : text.split("&")) {
      var pair = field.split("=", 2);
      result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
    }
    return result;
  }

  @ParameterizedTest @EnumSource(MailboxProvider.class)
  void authorizationCodePkceRedirectNonceAndRefreshContract(MailboxProvider provider) throws Exception {
    try (var stub = new Stub()) {
      var sender = stub.sender(provider, Duration.ofSeconds(5));
      var authorize = form(URI.create(sender.authorizationUrl("opaque-state", Pkce.challenge(VERIFIER), REDIRECT)).getRawQuery());
      assertThat(authorize).containsEntry("client_id", CLIENT).containsEntry("response_type", "code")
          .containsEntry("redirect_uri", REDIRECT).containsEntry("state", "opaque-state")
          .containsEntry("code_challenge_method", "S256").containsEntry("code_challenge", Pkce.challenge(VERIFIER))
          .containsEntry("nonce", Pkce.challenge(VERIFIER)).containsEntry("scope", provider.requestedScope());
      stub.reply = JSON.writeValueAsString(token(provider));
      var grant = sender.exchange("one-code", VERIFIER, REDIRECT);
      assertThat(form(stub.request)).containsExactlyInAnyOrderEntriesOf(Map.of("grant_type", "authorization_code",
          "code", "one-code", "code_verifier", VERIFIER, "redirect_uri", REDIRECT, "client_id", CLIENT, "client_secret", "client-secret"));
      assertThat(stub.contentType).isEqualTo("application/x-www-form-urlencoded");
      assertThat(grant.address()).isEqualTo("buyer@example.com");
      assertThat(provider.sendOnly(grant.scopes())).isTrue(); assertThat(provider.grantsSend(grant.scopes())).isTrue();
      assertThat(sender.refresh("refresh-secret").token()).isEqualTo("access-secret");
      assertThat(form(stub.request)).containsExactlyInAnyOrderEntriesOf(Map.of("grant_type", "refresh_token",
          "refresh_token", "refresh-secret", "client_id", CLIENT, "client_secret", "client-secret"));
      // Nonce also binds the identity reply to the exchanged PKCE verifier.
      assertThatThrownBy(() -> sender.exchange("one-code", "w".repeat(43), REDIRECT))
          .isInstanceOf(MailboxSender.MailboxFailure.class).hasMessage("REFUSED");
      stub.registeredExchange = Map.of("grant_type", "authorization_code", "code_verifier", VERIFIER, "redirect_uri", REDIRECT);
      assertThat(sender.exchange("one-code", VERIFIER, REDIRECT).address()).isEqualTo("buyer@example.com");
      for (String foreignRedirect : List.of("https://attacker.example/callback", REDIRECT + "/", "http://buyer.example/callback")) {
        int before = stub.calls.get();
        assertThatThrownBy(() -> sender.exchange("one-code", VERIFIER, foreignRedirect)).hasMessage("REVOKED");
        assertThat(stub.calls.get()).isEqualTo(before + 1);
      }
      assertThatThrownBy(() -> sender.exchange("one-code", "w".repeat(43), REDIRECT)).hasMessage("REVOKED");
    }
  }

  @ParameterizedTest @EnumSource(MailboxProvider.class)
  void identityClaimsAreValidatedBeforeAddressUse(MailboxProvider provider) throws Exception {
    try (var stub = new Stub()) {
      var sender = stub.sender(provider, Duration.ofSeconds(5));
      var invalid = new ArrayList<Map<String, Object>>();
      for (var entry : Map.<String, Object>of("iss", "https://attacker.example", "aud", "foreign-client",
          "exp", Instant.now().minusSeconds(1).getEpochSecond(), "nonce", "other-flow", "azp", "foreign-client",
          "sub", "", "nbf", Instant.now().plusSeconds(60).getEpochSecond()).entrySet()) {
        var c = claims(provider); c.put(entry.getKey(), entry.getValue()); invalid.add(c);
      }
      for (String missing : List.of("iss", "aud", "exp", "nonce", "sub")) {
        var c = claims(provider); c.remove(missing); invalid.add(c);
      }
      for (Object aud : List.of(List.of(CLIENT, "untrusted"), List.of("foreign"), 42, List.of())) {
        var c = claims(provider); c.put("aud", aud); invalid.add(c);
      }
      var c = claims(provider); c.put("exp", "9999999999"); invalid.add(c);
      c = claims(provider); c.put("email", "not-an-address"); invalid.add(c);
      if (provider == MailboxProvider.GOOGLE) {
        c = claims(provider); c.put("email_verified", false); invalid.add(c);
        c = claims(provider); c.put("email_verified", "true"); invalid.add(c);
      } else {
        for (var entry : Map.of("tid", "not-a-guid", "ver", "1.0").entrySet()) {
          c = claims(provider); c.put(entry.getKey(), entry.getValue()); invalid.add(c);
        }
        c = claims(provider); c.put("tid", "00000000-0000-0000-0000-000000000000"); invalid.add(c);
      }
      for (var bad : invalid) {
        var response = token(provider); response.put("id_token", jwt(bad)); stub.reply = JSON.writeValueAsString(response);
        assertThatThrownBy(() -> sender.exchange("code", VERIFIER, REDIRECT)).hasMessage("REFUSED");
      }
      c = claims(provider); c.put("azp", CLIENT); c.put("aud", List.of(CLIENT));
      if (provider == MailboxProvider.GOOGLE) c.put("iss", "accounts.google.com");
      else { c.remove("email"); c.put("preferred_username", "buyer@example.com"); }
      var response = token(provider); response.put("id_token", jwt(c)); stub.reply = JSON.writeValueAsString(response);
      assertThat(sender.exchange("code", VERIFIER, REDIRECT).address()).isEqualTo("buyer@example.com");
    }
  }

  @ParameterizedTest @EnumSource(MailboxProvider.class)
  void malformedTokenRepliesAndInvalidGrantAreSafe(MailboxProvider provider) throws Exception {
    try (var stub = new Stub()) {
      var sender = stub.sender(provider, Duration.ofSeconds(5));
      for (String malformed : List.of("", "null", "[]", "{broken", "{}")) {
        stub.reply = malformed;
        assertThatThrownBy(() -> sender.exchange("code", VERIFIER, REDIRECT)).hasMessage("REFUSED");
      }
      for (String field : List.of("access_token", "refresh_token", "id_token", "token_type", "expires_in")) {
        var reply = token(provider); reply.remove(field); stub.reply = JSON.writeValueAsString(reply);
        assertThatThrownBy(() -> sender.exchange("code", VERIFIER, REDIRECT)).hasMessage("REFUSED");
        reply.put(field, Map.of("private", "provider-detail")); stub.reply = JSON.writeValueAsString(reply);
        assertThatThrownBy(() -> sender.exchange("code", VERIFIER, REDIRECT)).hasMessage("REFUSED");
      }
      for (String id : List.of("x.y", "x.y.z", "e30.bnVsbA.c2ln", "e30.e30.", jwt(claims(provider)) + ".extra")) {
        var reply = token(provider); reply.put("id_token", id); stub.reply = JSON.writeValueAsString(reply);
        assertThatThrownBy(() -> sender.exchange("code", VERIFIER, REDIRECT)).hasMessage("REFUSED");
      }
      for (Object expiry : List.of(0, -1, 86_401, "3600")) {
        var reply = token(provider); reply.put("expires_in", expiry); stub.reply = JSON.writeValueAsString(reply);
        assertThatThrownBy(() -> sender.exchange("code", VERIFIER, REDIRECT)).hasMessage("REFUSED");
        assertThatThrownBy(() -> sender.refresh("refresh-secret")).hasMessage("REFUSED");
      }
      stub.reply = "{\"access_token\":\"private-secret\",\"token_type\":\"Basic\",\"expires_in\":3600}";
      assertThatThrownBy(() -> sender.refresh("refresh-secret")).hasMessage("REFUSED");
      for (int status : List.of(400, 401, 429, 503)) {
        stub.status = status; stub.reply = "{\"error\":\"invalid_grant\",\"private\":\"secret\"}";
        assertThatThrownBy(() -> sender.refresh("refresh-secret"))
            .hasMessage(status == 503 ? "UNAVAILABLE" : "REVOKED");
        assertThatThrownBy(() -> sender.exchange("code", VERIFIER, REDIRECT))
            .hasMessage(status == 503 ? "UNAVAILABLE" : "REVOKED");
      }
    }
  }

  @ParameterizedTest @EnumSource(MailboxProvider.class)
  void exactSendPayloadAndNoRetryForFailureResponses(MailboxProvider provider) throws Exception {
    try (var stub = new Stub()) {
      var sender = stub.sender(provider, Duration.ofSeconds(5));
      var message = new MailboxSender.OutgoingMessage("sales@dealer.example", "Quote — café", "Hello,\nExact body — $25,000.");
      assertThat(sender.send("access-secret", "buyer@example.com", message)).isEqualTo(MailboxSender.Delivery.SENT);
      assertThat(stub.authorization).isEqualTo("Bearer access-secret"); assertThat(stub.contentType).isEqualTo("application/json");
      var payload = JSON.readTree(stub.request);
      if (provider == MailboxProvider.GOOGLE) {
        String raw = new String(Base64.getUrlDecoder().decode(payload.path("raw").asText()), StandardCharsets.US_ASCII);
        assertThat(raw).isEqualTo(Rfc5322Message.render("buyer@example.com", message));
      } else {
        assertThat(payload).isEqualTo(JSON.valueToTree(Map.of("message", Map.of("subject", message.subject(),
            "body", Map.of("contentType", "Text", "content", message.body()), "toRecipients",
            List.of(Map.of("emailAddress", Map.of("address", message.to())))), "saveToSentItems", true)));
      }
      for (var response : Map.of(401, MailboxSender.Delivery.AUTH_FAILED, 429, MailboxSender.Delivery.RATE_LIMITED,
          403, MailboxSender.Delivery.RATE_LIMITED, 400, MailboxSender.Delivery.REJECTED,
          500, MailboxSender.Delivery.UNKNOWN, 503, MailboxSender.Delivery.UNKNOWN, 302, MailboxSender.Delivery.REJECTED).entrySet()) {
        stub.status = response.getKey(); stub.reply = "quota limitExceeded";
        int before = stub.calls.get();
        assertThat(sender.send("access-secret", "buyer@example.com", message)).isEqualTo(response.getValue());
        assertThat(stub.calls.get()).isEqualTo(before + 1);
      }
      stub.drop = true; int before = stub.calls.get();
      assertThat(sender.send("access-secret", "buyer@example.com", message)).isEqualTo(MailboxSender.Delivery.UNKNOWN);
      assertThat(stub.calls.get()).isEqualTo(before + 1);
    }
  }

  @ParameterizedTest @EnumSource(MailboxProvider.class)
  void timedOutSendIsUnknownAndNeverRetried(MailboxProvider provider) throws Exception {
    try (var stub = new Stub(); var worker = Executors.newVirtualThreadPerTaskExecutor()) {
      stub.entered = new CountDownLatch(1); stub.release = new CountDownLatch(1);
      var sender = stub.sender(provider, Duration.ofSeconds(1));
      var sending = worker.submit(() -> sender.send("access-secret", "buyer@example.com",
          new MailboxSender.OutgoingMessage("sales@dealer.example", "Subject", "Body")));
      assertThat(stub.entered.await(5, TimeUnit.SECONDS)).isTrue();
      try {
        assertThat(sending.get(5, TimeUnit.SECONDS)).isEqualTo(MailboxSender.Delivery.UNKNOWN);
        assertThat(stub.calls.get()).isEqualTo(1);
      } finally { stub.release.countDown(); }
    }
  }
}

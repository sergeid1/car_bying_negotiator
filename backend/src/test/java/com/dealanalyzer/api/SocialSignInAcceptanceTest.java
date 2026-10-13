package com.dealanalyzer.api;

import static org.assertj.core.api.Assertions.*;

import com.dealanalyzer.account.AccountDeletionWorker;
import com.dealanalyzer.recovery.RecoveryEmail;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Ticket 113: sign in with Google or Apple end to end against loopback stubs of the providers' token endpoints. The
 * stub checks the PKCE verifier against the challenge and Apple's ES256 client secret against the configured key.
 */
@ExtendWith(OutputCaptureExtension.class)
class SocialSignInAcceptanceTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String ROOT = "/api/v1/account/social-sign-in";
  private static final String GOOGLE_CLIENT = "google-client.apps.example", APPLE_CLIENT = "com.example.signin";
  private static final String TEAM = "TEAM123456", KEY_ID = "KEY1234567";
  private static final String ORIGIN = "http://localhost:5173/";
  private static final String FAILED_GOOGLE = "Sign-in with Google could not be completed. Try again or continue with your email.";
  private static final String FAILED_APPLE = "Sign-in with Apple could not be completed. Try again or continue with your email.";
  @TempDir Path directory;

  /** The providers' token endpoints. Each answer is an ID token for the attempt's nonce unless a test changes it. */
  private static final class Stub implements AutoCloseable {
    final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    final AtomicInteger calls = new AtomicInteger();
    final KeyPair apple;
    volatile Map<String, String> request = Map.of();
    volatile Map<String, Object> claims = Map.of();
    volatile int status = 200;
    volatile String challenge;
    volatile JsonNode appleSecret;

    Stub(KeyPair apple) throws Exception {
      this.apple = apple;
      server.createContext("/", exchange -> {
        calls.incrementAndGet();
        request = form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        boolean appleRequest = exchange.getRequestURI().getPath().startsWith("/apple/");
        int answer = status;
        if (answer == 200 && (challenge == null || !challenge.equals(challenge(request.get("code_verifier"))))) answer = 400;
        if (answer == 200 && appleRequest && !validAppleSecret(request.get("client_secret"))) answer = 401;
        byte[] body;
        try {
          body = (answer == 200 ? JSON.writeValueAsString(Map.of("access_token", "provider-access-secret",
              "token_type", appleRequest ? "bearer" : "Bearer", "expires_in", 3600, "id_token", jwt(claims)))
              : "{\"error\":\"invalid_grant\"}").getBytes(StandardCharsets.UTF_8);
        } catch (Exception failure) { throw new java.io.IOException(failure); }
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(answer, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
      });
      server.start();
    }

    /** Verifies the ES256 client secret in JOSE form with the JDK's own P1363 verifier. */
    private boolean validAppleSecret(String secret) {
      try {
        String[] parts = secret.split("\\.");
        var verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(apple.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        if (!verifier.verify(Base64.getUrlDecoder().decode(parts[2]))) return false;
        var header = JSON.readTree(Base64.getUrlDecoder().decode(parts[0]));
        appleSecret = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
        long now = Instant.now().getEpochSecond();
        return "ES256".equals(header.path("alg").asText()) && KEY_ID.equals(header.path("kid").asText())
            && TEAM.equals(appleSecret.path("iss").asText()) && APPLE_CLIENT.equals(appleSecret.path("sub").asText())
            && "https://appleid.apple.com".equals(appleSecret.path("aud").asText())
            && appleSecret.path("exp").asLong() > now && appleSecret.path("exp").asLong() <= now + 600;
      } catch (Exception invalid) { return false; }
    }

    String endpoint(String provider) { return "http://127.0.0.1:" + server.getAddress().getPort() + "/" + provider + "/token"; }
    @Override public void close() { server.stop(0); }
  }

  record Response(int status, JsonNode body, List<String> cookies, String location) {
    String cookie(String name) {
      return cookies.stream().filter(value -> value.startsWith(name + "=")).findFirst().orElse(null);
    }
    String cookieValue(String name) {
      String cookie = cookie(name);
      return cookie == null ? null : cookie.split(";", 2)[0].substring(name.length() + 1);
    }
  }

  /** One browser: it keeps its own binding cookie. */
  private final class Browser {
    final String base;
    String binding;
    Browser(String base) { this.base = base; }

    Response start(String provider) throws Exception {
      var response = send("POST", ROOT, Map.of("provider", provider), binding == null ? null : "social_sign_in_binding=" + binding, true);
      if (response.cookieValue("social_sign_in_binding") != null) binding = response.cookieValue("social_sign_in_binding");
      return response;
    }
    Response complete(String state) throws Exception {
      return send("POST", ROOT + "/complete", state == null ? Map.of() : Map.of("state", state),
          binding == null ? null : "social_sign_in_binding=" + binding, true);
    }
    Response send(String method, String path, Object body, String cookie, boolean accountRequest) throws Exception {
      var request = HttpRequest.newBuilder(URI.create(base + path));
      if (accountRequest) request.header("Account-Request", "1");
      if (cookie != null) request.header("Cookie", cookie);
      if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
      else request.header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
      return exchange(request.build());
    }
  }

  private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

  private static Response exchange(HttpRequest request) throws Exception {
    var response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
    byte[] bytes = response.body();
    JsonNode body;
    try { body = bytes.length == 0 ? JSON.nullNode() : JSON.readTree(bytes); } catch (Exception notJson) { body = JSON.nullNode(); }
    return new Response(response.statusCode(), body, response.headers().allValues("Set-Cookie"),
        response.headers().firstValue("Location").orElse(null));
  }

  /** The provider redirecting the Buyer back: Google with a query string, Apple with a cross-site form post. */
  private static Response callback(String base, String provider, Map<String, String> parameters) throws Exception {
    String query = parameters.entrySet().stream().map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
        + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
    String path = base + ROOT + "/" + provider + "/callback";
    return exchange(provider.equals("google")
        ? HttpRequest.newBuilder(URI.create(path + "?" + query)).GET().build()
        : HttpRequest.newBuilder(URI.create(path)).header("Content-Type", "application/x-www-form-urlencoded")
            .header("Origin", "https://appleid.apple.com").POST(HttpRequest.BodyPublishers.ofString(query)).build());
  }

  /** A started and called-back attempt whose ID token the stub will issue for {@code subject} and {@code email}. */
  private record Attempt(String state, Map<String, String> authorization) {}

  private static Attempt consent(Browser browser, Stub stub, String provider, String subject, String email,
      Consumer<Map<String, Object>> change) throws Exception {
    var started = browser.start(provider.toUpperCase(Locale.ROOT));
    assertThat(started.status()).isEqualTo(200);
    var authorization = form(URI.create(started.body().path("authorizationUrl").asText()).getRawQuery());
    String state = authorization.get("state");
    var back = callback(browser.base, provider, Map.of("code", "code-for-" + subject, "state", state));
    assertThat(back.status()).isEqualTo(303);
    assertThat(back.location()).isEqualTo(ORIGIN + "#social-sign-in=" + state);
    assertThat(back.cookies()).isEmpty();
    var claims = new LinkedHashMap<String, Object>();
    claims.put("iss", provider.equals("google") ? "https://accounts.google.com" : "https://appleid.apple.com");
    claims.put("aud", provider.equals("google") ? GOOGLE_CLIENT : APPLE_CLIENT);
    claims.put("sub", subject);
    claims.put("exp", Instant.now().plusSeconds(600).getEpochSecond());
    claims.put("iat", Instant.now().getEpochSecond());
    claims.put("nonce", authorization.get("nonce"));
    claims.put("email", email);
    claims.put("email_verified", provider.equals("google") ? (Object) true : "true");
    change.accept(claims);
    stub.claims = claims; stub.challenge = authorization.get("code_challenge"); stub.status = 200;
    return new Attempt(state, authorization);
  }

  private static Attempt consent(Browser browser, Stub stub, String provider, String subject, String email) throws Exception {
    return consent(browser, stub, provider, subject, email, claims -> {});
  }

  private static void refused(Response response, String detail, JdbcTemplate jdbc, long sessions) {
    assertThat(response.status()).isEqualTo(400);
    assertThat(response.body().path("code").asText()).isEqualTo("SOCIAL_SIGN_IN_FAILED");
    assertThat(response.body().path("detail").asText()).isEqualTo(detail);
    assertThat(response.cookie("account_session")).isNull();
    assertThat(count(jdbc, "buyer_account_sessions")).isEqualTo(sessions);
  }

  private static long count(JdbcTemplate jdbc, String table) {
    return jdbc.queryForObject("select count(*) from " + table, Long.class);
  }

  private static KeyPair appleKey() throws Exception {
    var generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    return generator.generateKeyPair();
  }

  private static String pem(KeyPair key) {
    return "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
        .encodeToString(key.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n";
  }

  private static String[] configuration(Stub stub, String... extra) {
    var properties = new ArrayList<>(List.of(
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef",
        "--account.social-sign-in.google.client-id=" + GOOGLE_CLIENT,
        "--account.social-sign-in.google.client-secret=google-client-secret",
        "--account.social-sign-in.google.token-endpoint=" + stub.endpoint("google"),
        "--account.social-sign-in.apple.client-id=" + APPLE_CLIENT,
        "--account.social-sign-in.apple.team-id=" + TEAM,
        "--account.social-sign-in.apple.key-id=" + KEY_ID,
        // An environment variable often carries the PEM with escaped line breaks.
        "--account.social-sign-in.apple.private-key=" + pem(stub.apple).replace("\n", "\\n"),
        "--account.social-sign-in.apple.token-endpoint=" + stub.endpoint("apple")));
    properties.addAll(List.of(extra));
    return properties.toArray(String[]::new);
  }

  @Test void googleAndAppleSignInCreateLinkAndReturnToAccounts(CapturedOutput output) throws Exception {
    BuyerAccountAcceptanceTest.sent.clear();
    try (var stub = new Stub(appleKey());
        var app = new HttpAcceptanceHarness(directory).startWithConfiguration("social-sign-in", false,
            BuyerAccountAcceptanceTest.EmailConfiguration.class, configuration(stub, "--security.request-email.peer-requests=1000"))) {
      var jdbc = app.context().getBean(JdbcTemplate.class);
      String base = "http://127.0.0.1:" + ((WebServerApplicationContext) app.context()).getWebServer().getPort();
      var browser = new Browser(base);

      // Discovery lists both configured providers; the start needs the account request header.
      assertThat(browser.send("GET", ROOT + "/providers", null, null, false).body().path("providers").toString())
          .isEqualTo("[\"GOOGLE\",\"APPLE\"]");
      assertThat(browser.send("POST", ROOT, Map.of("provider", "GOOGLE"), null, false).body().path("code").asText())
          .isEqualTo("ACCOUNT_REQUEST_REQUIRED");

      // A new Buyer with Google.
      var started = browser.start("GOOGLE");
      assertThat(started.cookie("social_sign_in_binding")).contains("HttpOnly", "SameSite=Strict", "Secure",
          "Path=/api/v1/account/social-sign-in", "Max-Age=600");
      var authorization = form(URI.create(started.body().path("authorizationUrl").asText()).getRawQuery());
      assertThat(started.body().path("authorizationUrl").asText()).startsWith("https://accounts.google.com/o/oauth2/v2/auth?");
      assertThat(authorization).containsEntry("client_id", GOOGLE_CLIENT).containsEntry("response_type", "code")
          .containsEntry("scope", "openid email").containsEntry("code_challenge_method", "S256")
          .containsEntry("redirect_uri", "http://localhost:5173/api/v1/account/social-sign-in/google/callback")
          .containsKeys("state", "nonce", "code_challenge").doesNotContainKey("response_mode");
      assertThat(authorization.get("nonce")).isNotEqualTo(authorization.get("code_challenge")).hasSize(43);
      String firstBinding = browser.binding;
      var google = consent(browser, stub, "google", "google-subject-1", "New.Buyer@Example.com");
      assertThat(browser.binding).as("a second start in the same browser keeps its binding").isEqualTo(firstBinding);
      assertThat(jdbc.queryForObject("select code_ciphertext from buyer_account_social_sign_in_attempts where code_ciphertext is not null",
          String.class)).doesNotContain("code-for-google-subject-1");
      var signedIn = browser.complete(google.state());
      assertThat(signedIn.status()).isEqualTo(200);
      assertThat(signedIn.body().path("provider").asText()).isEqualTo("GOOGLE");
      assertThat(Instant.parse(signedIn.body().path("expiresAt").asText())).isAfter(Instant.now());
      assertThat(signedIn.body().has("sessionToken")).isFalse();
      assertThat(signedIn.cookie("account_session")).contains("HttpOnly", "SameSite=Strict", "Path=/api/v1");
      assertThat(signedIn.cookie("social_sign_in_binding")).contains("Max-Age=0");
      assertThat(stub.request).containsEntry("grant_type", "authorization_code").containsEntry("code", "code-for-google-subject-1")
          .containsEntry("client_id", GOOGLE_CLIENT).containsEntry("client_secret", "google-client-secret")
          .containsEntry("redirect_uri", "http://localhost:5173/api/v1/account/social-sign-in/google/callback");
      String googleSession = signedIn.cookieValue("account_session");
      var account = browser.send("GET", "/api/v1/account", null, "account_session=" + googleSession, false);
      assertThat(account.body().path("contact").path("email").asText()).isEqualTo("new.buyer@example.com");
      assertThat(jdbc.queryForObject("select sign_in_method from buyer_account_sessions", String.class)).isEqualTo("GOOGLE");
      assertThat(jdbc.queryForObject("select count(*) from buyer_accounts where verified_at is not null", Long.class)).isOne();
      assertThat(jdbc.queryForObject("select subject_hash from buyer_account_identities", String.class)).doesNotContain("google-subject-1");
      assertThat(count(jdbc, "buyer_account_security_events")).as("a new account links without a security event").isZero();
      assertThat(jdbc.queryForObject("select count(*) from buyer_account_social_sign_in_attempts where consumed_at is null", Long.class))
          .as("only the first, unused attempt remains open").isOne();
      assertThat(jdbc.queryForObject("select count(*) from buyer_account_social_sign_in_attempts where consumed_at is not null "
          + "and (code_ciphertext is not null or verifier_ciphertext <> '' or nonce_ciphertext <> '')", Long.class))
          .as("a consumed attempt keeps no secrets").isZero();

      // The same Google identity returns to its account even when its email has changed.
      var returning = consent(browser, stub, "google", "google-subject-1", "changed@example.com");
      var again = browser.complete(returning.state());
      assertThat(again.status()).isEqualTo(200);
      assertThat(count(jdbc, "buyer_accounts")).isOne();
      assertThat(count(jdbc, "buyer_account_identities")).isOne();
      assertThat(count(jdbc, "buyer_account_security_events")).isZero();

      // A Buyer whose email already has an account links Apple to it, with a security event and notification.
      String emailSession = PasskeyAcceptanceTest.emailSignIn(app.client(), "existing@example.com");
      String existingId = jdbc.queryForObject("select account_id from buyer_account_sessions where sign_in_method = 'EMAIL_LINK'", String.class);
      var appleStart = browser.start("APPLE");
      var appleAuthorization = form(URI.create(appleStart.body().path("authorizationUrl").asText()).getRawQuery());
      assertThat(appleStart.body().path("authorizationUrl").asText()).startsWith("https://appleid.apple.com/auth/authorize?");
      assertThat(appleAuthorization).containsEntry("client_id", APPLE_CLIENT).containsEntry("response_mode", "form_post")
          .containsEntry("scope", "openid email")
          .containsEntry("redirect_uri", "http://localhost:5173/api/v1/account/social-sign-in/apple/callback");
      var apple = consent(browser, stub, "apple", "apple-subject-1", "Existing@Example.com");
      BuyerAccountAcceptanceTest.sent.clear();
      var linked = browser.complete(apple.state());
      assertThat(linked.status()).isEqualTo(200);
      assertThat(linked.body().path("provider").asText()).isEqualTo("APPLE");
      assertThat(stub.appleSecret.path("sub").asText()).isEqualTo(APPLE_CLIENT);
      assertThat(jdbc.queryForObject("select account_id from buyer_account_identities where provider = 'APPLE'", String.class))
          .isEqualTo(existingId);
      assertThat(jdbc.queryForList("select event_type from buyer_account_security_events where account_id = ?", String.class, existingId))
          .containsExactly("SOCIAL_SIGN_IN_LINKED");
      assertThat(BuyerAccountAcceptanceTest.sent).anyMatch(mail -> mail.purpose() == RecoveryEmail.Purpose.ACCOUNT_SECURITY
          && mail.recipient().equals("existing@example.com"));
      // Apple and Google sessions are strong sign-ins: they may add passkeys and start a mailbox connection.
      String appleSession = linked.cookieValue("account_session");
      assertThat(jdbc.queryForObject("select count(*) from buyer_account_sessions where sign_in_method = 'APPLE'", Long.class)).isOne();
      assertThat(PasskeyAcceptanceTest.request(app.client(), "POST", "/api/v1/account/passkeys/registration/options", Map.of(),
          appleSession).status()).isEqualTo(200);
      app.context().getBean(com.dealanalyzer.account.BuyerAccountService.class).requireMailboxStepUp(appleSession);
      app.context().getBean(com.dealanalyzer.account.BuyerAccountService.class).requireMailboxStepUp(googleSession);

      // Callback outcomes: a cancelled consent, an unknown state and the wrong provider's callback.
      var cancelled = browser.start("GOOGLE");
      String cancelledState = form(URI.create(cancelled.body().path("authorizationUrl").asText()).getRawQuery()).get("state");
      assertThat(callback(base, "google", Map.of("error", "access_denied", "state", cancelledState)).location())
          .isEqualTo(ORIGIN + "#social-sign-in-error=cancelled");
      assertThat(callback(base, "google", Map.of("code", "x", "state", cancelledState)).location())
          .as("a cancelled attempt is consumed").isEqualTo(ORIGIN + "#social-sign-in-error=failed");
      assertThat(callback(base, "google", Map.of("code", "x", "state", "unknown-state")).location())
          .isEqualTo(ORIGIN + "#social-sign-in-error=failed");
      var appleCancelled = browser.start("APPLE");
      String appleCancelledState = form(URI.create(appleCancelled.body().path("authorizationUrl").asText()).getRawQuery()).get("state");
      assertThat(callback(base, "apple", Map.of("error", "user_cancelled_authorize", "state", appleCancelledState)).location())
          .isEqualTo(ORIGIN + "#social-sign-in-error=cancelled");
      var crossed = browser.start("APPLE");
      String crossedState = form(URI.create(crossed.body().path("authorizationUrl").asText()).getRawQuery()).get("state");
      assertThat(callback(base, "google", Map.of("code", "x", "state", crossedState)).location())
          .isEqualTo(ORIGIN + "#social-sign-in-error=failed");

      // Every refusal is the one 400 detail, signs nobody in and consumes the attempt.
      long sessions = count(jdbc, "buyer_account_sessions");
      int calls = stub.calls.get();
      var missing = consent(browser, stub, "google", "attacker", "attacker@example.com");
      var stranger = new Browser(base);
      refused(stranger.complete(missing.state()), FAILED_GOOGLE, jdbc, sessions);
      refused(browser.complete(missing.state()), FAILED_GOOGLE, jdbc, sessions);
      var mismatched = consent(browser, stub, "google", "google-subject-1", "new.buyer@example.com");
      stranger.start("GOOGLE");
      refused(stranger.complete(mismatched.state()), FAILED_GOOGLE, jdbc, sessions);
      refused(browser.complete(mismatched.state()), FAILED_GOOGLE, jdbc, sessions);
      var expired = consent(browser, stub, "apple", "apple-subject-1", "existing@example.com");
      jdbc.update("update buyer_account_social_sign_in_attempts set expires_at = ?", Timestamp.from(Instant.now().minusSeconds(1)));
      refused(browser.complete(expired.state()), FAILED_APPLE, jdbc, sessions);
      var uncalled = browser.start("GOOGLE");
      refused(browser.complete(form(URI.create(uncalled.body().path("authorizationUrl").asText()).getRawQuery()).get("state")),
          FAILED_GOOGLE, jdbc, sessions);
      assertThat(stub.calls.get()).as("no refused attempt reached the provider").isEqualTo(calls);
      var unknown = browser.complete("unknown-state");
      assertThat(unknown.status()).isEqualTo(400);
      assertThat(unknown.body().path("code").asText()).isEqualTo("SOCIAL_SIGN_IN_FAILED");
      var reused = consent(browser, stub, "google", "google-subject-1", "new.buyer@example.com");
      assertThat(browser.complete(reused.state()).status()).isEqualTo(200);
      sessions = count(jdbc, "buyer_account_sessions");
      refused(browser.complete(reused.state()), FAILED_GOOGLE, jdbc, sessions);
      refused(browser.complete(consent(browser, stub, "google", "google-subject-1", "new.buyer@example.com",
          claims -> claims.put("nonce", "another-nonce")).state()), FAILED_GOOGLE, jdbc, sessions);
      refused(browser.complete(consent(browser, stub, "google", "google-subject-1", "new.buyer@example.com",
          claims -> claims.put("aud", "another-client")).state()), FAILED_GOOGLE, jdbc, sessions);
      refused(browser.complete(consent(browser, stub, "google", "google-subject-1", "new.buyer@example.com",
          claims -> claims.put("aud", List.of(GOOGLE_CLIENT, "another-client"))).state()), FAILED_GOOGLE, jdbc, sessions);
      refused(browser.complete(consent(browser, stub, "google", "google-subject-1", "new.buyer@example.com",
          claims -> claims.put("iss", "https://appleid.apple.com")).state()), FAILED_GOOGLE, jdbc, sessions);
      refused(browser.complete(consent(browser, stub, "google", "google-subject-1", "new.buyer@example.com",
          claims -> claims.put("exp", Instant.now().minusSeconds(5).getEpochSecond())).state()), FAILED_GOOGLE, jdbc, sessions);
      refused(browser.complete(consent(browser, stub, "google", "google-subject-2", "unverified@example.com",
          claims -> claims.put("email_verified", false)).state()), FAILED_GOOGLE, jdbc, sessions);
      refused(browser.complete(consent(browser, stub, "apple", "apple-subject-2", "unverified@example.com",
          claims -> claims.put("email_verified", "false")).state()), FAILED_APPLE, jdbc, sessions);
      assertThat(count(jdbc, "buyer_accounts")).as("no refusal created an account").isEqualTo(2);
      var outage = consent(browser, stub, "google", "google-subject-1", "new.buyer@example.com");
      stub.status = 503;
      var unavailable = browser.complete(outage.state());
      assertThat(unavailable.status()).isEqualTo(503);
      assertThat(unavailable.body().path("code").asText()).isEqualTo("SOCIAL_SIGN_IN_UNAVAILABLE");
      assertThat(count(jdbc, "buyer_account_sessions")).isEqualTo(sessions);

      // The export lists linked providers and dates, never the subject.
      byte[] export = app.client().post().uri("/api/v1/account/export").header("Account-Request", "1")
          .header("Cookie", "account_session=" + emailSession).retrieve().body(byte[].class);
      try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(export))) {
        java.util.zip.ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
          String text = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
          assertThat(text).doesNotContain("apple-subject-1", "subject_hash", "subjectHash");
          if (entry.getName().equals("account.json")) {
            var identities = JSON.readTree(text).path("socialSignIns");
            assertThat(identities).hasSize(1);
            assertThat(identities.get(0).path("provider").asText()).isEqualTo("APPLE");
            assertThat(identities.get(0).path("linkedAt").isMissingNode()).isFalse();
            assertThat(identities.get(0).size()).isEqualTo(2);
          }
        }
      }

      // An account awaiting deletion cannot sign in; deletion removes its identities.
      assertThat(browser.send("POST", "/api/v1/account/deletion", null, "account_session=" + emailSession, true).status()).isEqualTo(202);
      sessions = count(jdbc, "buyer_account_sessions");
      refused(browser.complete(consent(browser, stub, "apple", "apple-subject-1", "existing@example.com").state()),
          FAILED_APPLE, jdbc, sessions);
      refused(browser.complete(consent(browser, stub, "google", "google-subject-3", "existing@example.com").state()),
          FAILED_GOOGLE, jdbc, sessions);
      app.context().getBean(AccountDeletionWorker.class).runOnce();
      assertThat(jdbc.queryForObject("select count(*) from buyer_account_identities where account_id = ?", Long.class, existingId)).isZero();
      assertThat(jdbc.queryForList("select provider from buyer_account_identities", String.class)).containsExactly("GOOGLE");

      assertThat(output).doesNotContain("code-for-", "google-client-secret", "provider-access-secret", "google-subject-1",
          "apple-subject-1", "new.buyer@example.com", stub.challenge);
    }
  }

  @Test void discoveryListsOnlyConfiguredProvidersAndRetriesLeaveTheEmailQuota() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("social-sign-in-google", false,
        BuyerAccountAcceptanceTest.EmailConfiguration.class,
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef",
        "--account.social-sign-in.google.client-id=" + GOOGLE_CLIENT,
        // Partial Apple settings outside pilot/production simply leave Apple off.
        "--account.social-sign-in.apple.client-id=" + APPLE_CLIENT,
        "--security.request-email.peer-requests=3")) {
      String base = "http://127.0.0.1:" + ((WebServerApplicationContext) app.context()).getWebServer().getPort();
      var browser = new Browser(base);
      assertThat(browser.send("GET", ROOT + "/providers", null, null, false).body().path("providers").toString()).isEqualTo("[]");
      var unavailable = browser.start("GOOGLE");
      assertThat(unavailable.status()).isEqualTo(404);
      assertThat(unavailable.body().path("code").asText()).isEqualTo("SOCIAL_SIGN_IN_UNAVAILABLE");
      assertThat(callback(base, "google", Map.of("code", "x", "state", "unknown")).location()).isEqualTo(ORIGIN + "#social-sign-in-error=failed");
      assertThat(browser.start("APPLE").status()).isEqualTo(404);
      // Start and callback send no email: retries stay under the general API limiter and leave the email quota alone.
      for (int i = 0; i < 5; i++) {
        assertThat(browser.start("GOOGLE").status()).isEqualTo(404);
        assertThat(callback(base, "google", Map.of("code", "x", "state", "unknown")).status()).isEqualTo(303);
        assertThat(callback(base, "apple", Map.of("code", "x", "state", "unknown")).status()).isEqualTo(303);
      }
      for (int i = 0; i < 3; i++) assertThat(emailSignInRequest(base, "buyer" + i + "@example.com").status())
          .as("emailed sign-in still has its whole quota").isEqualTo(202);
      assertThat(emailSignInRequest(base, "buyer@example.com").status()).isEqualTo(429);
    }
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("social-sign-in-google-only", false,
        BuyerAccountAcceptanceTest.EmailConfiguration.class,
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef",
        "--account.social-sign-in.google.client-id=" + GOOGLE_CLIENT,
        "--account.social-sign-in.google.client-secret=google-client-secret")) {
      String base = "http://127.0.0.1:" + ((WebServerApplicationContext) app.context()).getWebServer().getPort();
      var browser = new Browser(base);
      assertThat(browser.send("GET", ROOT + "/providers", null, null, false).body().path("providers").toString()).isEqualTo("[\"GOOGLE\"]");
      assertThat(browser.start("APPLE").body().path("code").asText()).isEqualTo("SOCIAL_SIGN_IN_UNAVAILABLE");
      assertThat(browser.start("GOOGLE").status()).isEqualTo(200);
    }
  }

  private static Response emailSignInRequest(String base, String email) throws Exception {
    return exchange(HttpRequest.newBuilder(URI.create(base + "/api/v1/account/sign-in-requests"))
        .header("Content-Type", "application/json").header("Idempotency-Key", UUID.randomUUID().toString())
        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("email", email)))).build());
  }

  private static String challenge(String verifier) {
    if (verifier == null) return null;
    try {
      return Base64.getUrlEncoder().withoutPadding().encodeToString(
          MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    } catch (Exception impossible) { throw new IllegalStateException(impossible); }
  }

  private static String jwt(Map<String, Object> claims) throws Exception {
    return Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"RS256\",\"kid\":\"k\"}".getBytes(StandardCharsets.UTF_8))
        + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(claims)) + ".c2lnbmF0dXJl";
  }

  private static Map<String, String> form(String text) {
    var result = new LinkedHashMap<String, String>();
    if (text == null || text.isEmpty()) return result;
    for (String field : text.split("&")) {
      var pair = field.split("=", 2);
      result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), pair.length > 1 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
    }
    return result;
  }
}

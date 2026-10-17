package com.dealanalyzer.api;

import static org.assertj.core.api.Assertions.*;

import com.dealanalyzer.recovery.RecoveryEmail;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/**
 * Ticket 136 through HTTP: every request arrives from one loopback peer, as it does behind the Worker and the pilot
 * proxy, and only a request carrying the origin secret is keyed by the visitor address the Worker sets.
 */
@ExtendWith(OutputCaptureExtension.class)
class ClientAddressAcceptanceTest {
  private static final String KEY = "--document.storage.encryption-key=0123456789abcdef0123456789abcdef";
  private static final String SECRET = "d".repeat(64);
  private static final String ORIGIN = "--security.client-address.origin-secret=" + SECRET;
  private static final List<String> VISITORS = List.of("203.0.113.71", "198.51.100.72", "2001:db8:73:74::1");
  private static final List<String> sent = new CopyOnWriteArrayList<>();
  @TempDir Path directory;

  @TestConfiguration static class EmailConfiguration {
    @Bean @Primary RecoveryEmail clientAddressTestEmail() { return (recipient, link) -> sent.add(recipient); }
  }

  @Import({TotoAcceptanceTest.Configuration.class, EmailConfiguration.class})
  @TestConfiguration static class WithToto {}

  @BeforeEach void reset() {
    sent.clear();
    TotoAcceptanceTest.vendor.set(TotoAcceptanceTest.Vendor.ANSWER);
    TotoAcceptanceTest.clock.set(Instant.now());
  }

  @Test void visitorsBehindTheProxyGetSeparateQuotasAndForgedAddressesAreIgnored() {
    try (var app = new HttpAcceptanceHarness(directory).start("client-address", false, ORIGIN,
        "--security.rate-limit.requests=3")) {
      var client = app.client();

      // API quota: each verified visitor has its own three requests; the next is refused for that visitor only.
      for (String visitor : VISITORS.subList(0, 2)) {
        for (int i = 0; i < 3; i++) assertThat(missing(client, SECRET, visitor)).isEqualTo(404);
        assertThat(missing(client, SECRET, visitor)).isEqualTo(429);
      }
      // Another host in the same IPv6 /64 shares that visitor's bucket; the next /64 does not.
      for (int i = 0; i < 3; i++) assertThat(missing(client, SECRET, "2001:db8:73:74::" + (i + 2))).isEqualTo(404);
      assertThat(missing(client, SECRET, VISITORS.get(2))).isEqualTo(429);
      assertThat(missing(client, SECRET, "2001:db8:73:75::1")).isEqualTo(404);

      // Without the secret, or with a wrong one, a forged visitor address is ignored: all such requests share the
      // proxy peer's bucket, however the address and forwarding headers rotate.
      for (int i = 0; i < 3; i++) assertThat(missing(client, i == 0 ? null : "e".repeat(64), "192.0.2." + i)).isEqualTo(404);
      assertThat(missing(client, null, "192.0.2.9")).isEqualTo(429);
      assertThat(missing(client, "forged", VISITORS.get(1).replace("72", "99"))).isEqualTo(429);
    }
  }

  @Test void emailAndTotoCapsCountPerVisitorWithoutStoringOrLoggingTheAddress(CapturedOutput logs) throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("client-address-caps", false, WithToto.class, KEY,
        ORIGIN, "--security.request-email.peer-requests=2", "--toto.enabled=true", "--toto.client-daily-questions=2")) {
      var client = app.client();

      // Sign-in email quota: two per visitor, independently.
      for (String visitor : VISITORS.subList(0, 2)) {
        for (int i = 0; i < 2; i++) assertThat(signIn(client, SECRET, visitor, visitor.length() + "-" + i)).isEqualTo(202);
        assertThat(signIn(client, SECRET, visitor, "over")).isEqualTo(429);
      }
      assertThat(sent).hasSize(4);
      // An unverified copy of a fresh address shares the peer bucket.
      for (int i = 0; i < 2; i++) assertThat(signIn(client, null, "192.0.2." + i, "peer-" + i)).isEqualTo(202);
      assertThat(signIn(client, null, VISITORS.get(2), "peer-over")).isEqualTo(429);

      // Toto client cap: two questions a day for each visitor without an account.
      for (String visitor : VISITORS) {
        for (int i = 0; i < 2; i++) assertThat(ask(client, SECRET, visitor)).as(visitor).isEqualTo(200);
        assertThat(ask(client, SECRET, visitor)).as(visitor).isEqualTo(429);
      }
      // Forged addresses without the secret are one client: the peer's cap.
      for (int i = 0; i < 2; i++) assertThat(ask(client, null, "192.0.2." + (10 + i))).isEqualTo(200);
      assertThat(ask(client, "forged", "192.0.2.12")).isEqualTo(429);

      // The address is a quota key only: no row and no log line holds it.
      var jdbc = new JdbcTemplate(app.context().getBean(javax.sql.DataSource.class));
      String database = String.join("\n", jdbc.queryForList("script", String.class));
      for (String address : List.of("203.0.113.71", "198.51.100.72", "2001:db8:73:74", "192.0.2.1")) {
        assertThat(database).doesNotContain(address);
        assertThat(logs.getAll()).doesNotContain(address);
      }
      assertThat(logs.getAll()).doesNotContain(SECRET);
    }
  }

  @Test void aMalformedOriginSecretRefusesStartup() {
    assertThatThrownBy(() -> new HttpAcceptanceHarness(directory).start("client-address-invalid", false,
        "--security.client-address.origin-secret=weak"))
        .hasRootCauseMessage("security.client-address.origin-secret must be 32 random bytes encoded as hex");
  }

  private static RestClient.RequestHeadersSpec<?> proxied(RestClient.RequestHeadersSpec<?> request, String verify, String visitor) {
    request.header(ClientAddress.HEADER, visitor).header("X-Forwarded-For", visitor).header("Forwarded", "for=" + visitor);
    if (verify != null) request.header(ClientAddress.VERIFY, verify);
    return request;
  }

  private static int missing(RestClient client, String verify, String visitor) {
    return proxied(client.get().uri("/api/v1/cases/missing"), verify, visitor)
        .exchange((request, response) -> response.getStatusCode().value());
  }

  private static int signIn(RestClient client, String verify, String visitor, String label) {
    return proxied(client.post().uri("/api/v1/account/sign-in-requests").contentType(MediaType.APPLICATION_JSON)
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .body(Map.of("email", "visitor-" + label.replace(':', '-') + "@example.com")), verify, visitor)
        .exchange((request, response) -> response.getStatusCode().value());
  }

  private static int ask(RestClient client, String verify, String visitor) {
    return proxied(client.post().uri("/api/v1/toto/answers").contentType(MediaType.APPLICATION_JSON)
        .body("{\"question\":\"What is a doc fee?\",\"turns\":[]}"), verify, visitor)
        .exchange((request, response) -> response.getStatusCode().value());
  }
}

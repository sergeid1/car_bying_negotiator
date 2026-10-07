package com.dealanalyzer.mailbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The OAuth 2.0 authorization code flow with PKCE that Google and the Microsoft identity platform share, and
 * the mapping of a send response to a {@link MailboxSender.Delivery}. Response bodies, tokens and addresses are
 * never logged or put in exception messages.
 */
abstract class OAuthMailboxSender implements MailboxSender {
  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

  private final ObjectMapper json = new ObjectMapper();
  private final HttpClient http;
  private final String clientId;
  private final String clientSecret;

  OAuthMailboxSender(String clientId, String clientSecret) {
    if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank())
      throw new IllegalStateException(provider().label() + " client credentials are required for mailbox.sender=live");
    this.clientId = clientId; this.clientSecret = clientSecret;
    this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
  }

  abstract String authorizationEndpoint();
  abstract String tokenEndpoint();
  /** Extra authorization parameters, such as Google's offline access. */
  Map<String, String> authorizationParameters() { return Map.of(); }
  /** The sending address from the ID token's claims. */
  abstract String address(JsonNode claims);

  @Override public String authorizationUrl(String state, String codeChallenge, String redirectUri) {
    var parameters = new LinkedHashMap<String, String>();
    parameters.put("client_id", clientId);
    parameters.put("response_type", "code");
    parameters.put("redirect_uri", redirectUri);
    parameters.put("scope", provider().requestedScope());
    parameters.put("state", state);
    parameters.put("code_challenge", codeChallenge);
    parameters.put("code_challenge_method", "S256");
    parameters.putAll(authorizationParameters());
    return authorizationEndpoint() + "?" + form(parameters);
  }

  @Override public Grant exchange(String code, String codeVerifier, String redirectUri) {
    JsonNode token = token(Map.of("grant_type", "authorization_code", "code", code, "code_verifier", codeVerifier,
        "redirect_uri", redirectUri));
    String refresh = token.path("refresh_token").asText("");
    String idToken = token.path("id_token").asText("");
    if (refresh.isBlank() || idToken.isBlank()) throw new MailboxFailure(MailboxFailure.Kind.REFUSED);
    Set<String> scopes = Set.of(token.path("scope").asText("").trim().split("\\s+"));
    String address = address(claims(idToken));
    if (address == null || address.isBlank()) throw new MailboxFailure(MailboxFailure.Kind.REFUSED);
    return new Grant(token.path("access_token").asText(), refresh, expiry(token), scopes, address.toLowerCase(Locale.ROOT));
  }

  @Override public AccessToken refresh(String refreshToken) {
    JsonNode token = token(Map.of("grant_type", "refresh_token", "refresh_token", refreshToken));
    return new AccessToken(token.path("access_token").asText(), expiry(token));
  }

  /** Posts a send request. Only a 2xx answer is a send; a timeout, lost connection or 5xx is unclear. */
  Delivery post(String url, String accessToken, String contentType, String body) {
    var request = HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT)
        .header("Authorization", "Bearer " + accessToken).header("Content-Type", contentType)
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
    HttpResponse<String> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException lost) {
      return Delivery.UNKNOWN;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return Delivery.UNKNOWN;
    }
    int status = response.statusCode();
    if (status >= 200 && status < 300) return Delivery.SENT;
    if (status >= 500) return Delivery.UNKNOWN;
    if (status == 429) return Delivery.RATE_LIMITED;
    if (status == 401) return Delivery.AUTH_FAILED;
    String lower = response.body() == null ? "" : response.body().toLowerCase(Locale.ROOT);
    if (status == 403 && (lower.contains("ratelimitexceeded") || lower.contains("quota") || lower.contains("limitexceeded")))
      return Delivery.RATE_LIMITED;
    return Delivery.REJECTED;
  }

  private JsonNode token(Map<String, String> grant) {
    var parameters = new LinkedHashMap<>(grant);
    parameters.put("client_id", clientId);
    parameters.put("client_secret", clientSecret);
    var request = HttpRequest.newBuilder(URI.create(tokenEndpoint())).timeout(REQUEST_TIMEOUT)
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(form(parameters))).build();
    HttpResponse<String> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException unreachable) {
      throw new MailboxFailure(MailboxFailure.Kind.UNAVAILABLE);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new MailboxFailure(MailboxFailure.Kind.UNAVAILABLE);
    }
    JsonNode body;
    try { body = json.readTree(response.body()); }
    catch (IOException unreadable) { throw new MailboxFailure(MailboxFailure.Kind.UNAVAILABLE); }
    if (response.statusCode() >= 500) throw new MailboxFailure(MailboxFailure.Kind.UNAVAILABLE);
    // invalid_grant: the Buyer withdrew access, changed their password, or the grant expired.
    if ("invalid_grant".equals(body.path("error").asText())) throw new MailboxFailure(MailboxFailure.Kind.REVOKED);
    if (response.statusCode() != 200 || body.path("access_token").asText("").isBlank())
      throw new MailboxFailure(MailboxFailure.Kind.REFUSED);
    return body;
  }

  /**
   * The ID token's claims. It came straight from the provider's token endpoint over TLS in exchange for the
   * client secret, so its signature need not be checked again (OpenID Connect Core 3.1.3.7).
   */
  private JsonNode claims(String idToken) {
    String[] parts = idToken.split("\\.");
    if (parts.length < 2) throw new MailboxFailure(MailboxFailure.Kind.REFUSED);
    try { return json.readTree(Base64.getUrlDecoder().decode(parts[1])); }
    catch (IOException | IllegalArgumentException unreadable) { throw new MailboxFailure(MailboxFailure.Kind.REFUSED); }
  }

  private static Instant expiry(JsonNode token) {
    long seconds = token.path("expires_in").asLong(3600);
    return Instant.now().plusSeconds(Math.max(60, Math.min(seconds, 86_400)));
  }

  static String form(Map<String, String> parameters) {
    return parameters.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
        .collect(Collectors.joining("&"));
  }

  private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

  void bestEffort(String url, String body) {
    var request = HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT)
        .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build();
    try { http.send(request, HttpResponse.BodyHandlers.discarding()); }
    catch (IOException ignored) { /* The local tokens are already deleted. */ }
    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
  }
}

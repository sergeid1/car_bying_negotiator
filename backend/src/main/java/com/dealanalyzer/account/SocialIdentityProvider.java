package com.dealanalyzer.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The OpenID Connect authorization code flow with PKCE and a nonce, as Google and Sign in with Apple implement it.
 * Only the ID token's subject and verified email are used; no provider token is kept. Response bodies, codes,
 * tokens and addresses are never logged or put in exception messages.
 */
abstract class SocialIdentityProvider {
  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
  private static final ObjectMapper JSON = new ObjectMapper();

  /** The provider's stable subject and the verified email address. */
  record Identity(String subject, String email) {
    @Override public String toString() { return "Identity[redacted]"; }
  }

  static final class Failure extends RuntimeException {
    enum Kind { UNAVAILABLE, REFUSED }
    final Kind kind;
    Failure(Kind kind) { super(kind.name(), null, false, false); this.kind = kind; }
  }

  private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
      .followRedirects(HttpClient.Redirect.NEVER).build();
  final String clientId;
  private final String authorizationEndpoint;
  private final String tokenEndpoint;

  SocialIdentityProvider(String clientId, String authorizationEndpoint, String tokenEndpoint) {
    this.clientId = clientId; this.authorizationEndpoint = authorizationEndpoint; this.tokenEndpoint = tokenEndpoint;
  }

  abstract SocialProvider provider();
  abstract Set<String> issuers();
  /** The client secret sent with one token request. */
  abstract String clientSecret();
  /** Extra authorization parameters, such as Apple's form post. */
  Map<String, String> authorizationParameters() { return Map.of(); }

  String authorizationUrl(String state, String verifier, String nonce, String redirectUri) {
    var parameters = new LinkedHashMap<String, String>();
    parameters.put("client_id", clientId);
    parameters.put("response_type", "code");
    parameters.put("redirect_uri", redirectUri);
    parameters.put("scope", "openid email");
    parameters.put("state", state);
    parameters.put("code_challenge", challenge(verifier));
    parameters.put("code_challenge_method", "S256");
    parameters.put("nonce", nonce);
    parameters.putAll(authorizationParameters());
    return authorizationEndpoint + "?" + form(parameters);
  }

  /** Redeems the code and checks the ID token. Only a verified email is returned. */
  Identity exchange(String code, String verifier, String nonce, String redirectUri) {
    var parameters = new LinkedHashMap<String, String>();
    parameters.put("grant_type", "authorization_code");
    parameters.put("code", code);
    parameters.put("code_verifier", verifier);
    parameters.put("redirect_uri", redirectUri);
    parameters.put("client_id", clientId);
    parameters.put("client_secret", clientSecret());
    var request = HttpRequest.newBuilder(URI.create(tokenEndpoint)).timeout(REQUEST_TIMEOUT)
        .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(form(parameters))).build();
    HttpResponse<String> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException unreachable) {
      throw new Failure(Failure.Kind.UNAVAILABLE);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new Failure(Failure.Kind.UNAVAILABLE);
    }
    if (response.statusCode() >= 500) throw new Failure(Failure.Kind.UNAVAILABLE);
    JsonNode body;
    try { body = JSON.readTree(response.body()); }
    catch (IOException unreadable) { throw refused(); }
    if (response.statusCode() != 200 || body == null || !body.isObject()) throw refused();
    JsonNode claims = claims(text(body, "id_token"), nonce);
    String email = text(claims, "email");
    if (!verified(claims.path("email_verified")) || email.length() > 254 || !email.matches("[^\\s@<>]+@[^\\s@<>]+"))
      throw refused();
    return new Identity(text(claims, "sub"), email);
  }

  /**
   * The ID token's claims. It came straight from the provider's token endpoint over TLS in exchange for the client
   * secret, so its signature need not be checked again (OpenID Connect Core 3.1.3.7).
   */
  private JsonNode claims(String idToken, String nonce) {
    String[] parts = idToken.split("\\.", -1);
    if (parts.length != 3 || parts[2].isBlank()) throw refused();
    try {
      JsonNode header = JSON.readTree(Base64.getUrlDecoder().decode(parts[0]));
      JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
      Base64.getUrlDecoder().decode(parts[2]);
      if (header == null || !"RS256".equals(text(header, "alg")) || claims == null || !claims.isObject()) throw refused();
      JsonNode aud = claims.path("aud");
      // Only this client is trusted; arrays must contain exactly this client, never extra audiences.
      boolean audience = aud.isTextual() ? clientId.equals(aud.textValue())
          : aud.isArray() && aud.size() == 1 && aud.get(0).isTextual() && clientId.equals(aud.get(0).textValue());
      long now = Instant.now().getEpochSecond();
      String subject = text(claims, "sub");
      if (!issuers().contains(text(claims, "iss")) || !audience || subject.isBlank() || subject.length() > 255
          || !claims.path("exp").isIntegralNumber() || !claims.path("exp").canConvertToLong()
          || claims.path("exp").longValue() <= now
          || !MessageDigest.isEqual(nonce.getBytes(StandardCharsets.UTF_8), text(claims, "nonce").getBytes(StandardCharsets.UTF_8))
          || (claims.has("azp") && !clientId.equals(text(claims, "azp")))
          || (claims.has("nbf") && (!claims.path("nbf").isIntegralNumber() || !claims.path("nbf").canConvertToLong()
              || claims.path("nbf").asLong() > now)))
        throw refused();
      return claims;
    } catch (IOException | IllegalArgumentException unreadable) { throw refused(); }
  }

  /** Google sends a boolean; Apple has sent both a boolean and the string "true". */
  private static boolean verified(JsonNode value) {
    return value.isBoolean() ? value.booleanValue() : value.isTextual() && "true".equals(value.textValue());
  }

  static String challenge(String verifier) {
    try {
      return Base64.getUrlEncoder().withoutPadding().encodeToString(
          MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    } catch (GeneralSecurityException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
  }

  static String text(JsonNode node, String field) { return node.path(field).isTextual() ? node.path(field).textValue() : ""; }
  private static Failure refused() { return new Failure(Failure.Kind.REFUSED); }

  static String form(Map<String, String> parameters) {
    return parameters.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
        .collect(Collectors.joining("&"));
  }

  private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

  /** Google Identity: a confidential web client with the {@code openid email} scopes. */
  static final class Google extends SocialIdentityProvider {
    static final String AUTHORIZATION = "https://accounts.google.com/o/oauth2/v2/auth";
    static final String TOKEN = "https://oauth2.googleapis.com/token";
    private final String clientSecret;

    Google(String clientId, String clientSecret, String authorizationEndpoint, String tokenEndpoint) {
      super(clientId, authorizationEndpoint, tokenEndpoint);
      this.clientSecret = clientSecret;
    }

    @Override SocialProvider provider() { return SocialProvider.GOOGLE; }
    @Override Set<String> issuers() { return Set.of("https://accounts.google.com", "accounts.google.com"); }
    @Override String clientSecret() { return clientSecret; }
    @Override Map<String, String> authorizationParameters() { return Map.of("prompt", "select_account"); }
  }

  /**
   * Sign in with Apple. Asking for {@code email} requires {@code response_mode=form_post}. The client secret is a
   * short-lived ES256 JWT signed with the Sign in with Apple key for each token request.
   */
  static final class Apple extends SocialIdentityProvider {
    static final String AUTHORIZATION = "https://appleid.apple.com/auth/authorize";
    static final String TOKEN = "https://appleid.apple.com/auth/token";
    static final String AUDIENCE = "https://appleid.apple.com";
    private final String teamId;
    private final String keyId;
    private final PrivateKey key;

    Apple(String clientId, String teamId, String keyId, String privateKeyPem, String authorizationEndpoint, String tokenEndpoint) {
      super(clientId, authorizationEndpoint, tokenEndpoint);
      this.teamId = teamId; this.keyId = keyId; this.key = parse(privateKeyPem);
    }

    @Override SocialProvider provider() { return SocialProvider.APPLE; }
    @Override Set<String> issuers() { return Set.of(AUDIENCE); }
    @Override Map<String, String> authorizationParameters() { return Map.of("response_mode", "form_post"); }

    @Override String clientSecret() {
      long now = Instant.now().getEpochSecond();
      var header = new LinkedHashMap<String, Object>();
      header.put("alg", "ES256"); header.put("kid", keyId);
      var claims = new LinkedHashMap<String, Object>();
      claims.put("iss", teamId); claims.put("iat", now); claims.put("exp", now + 300);
      claims.put("aud", AUDIENCE); claims.put("sub", clientId);
      try {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        String signingInput = encoder.encodeToString(JSON.writeValueAsBytes(header)) + "."
            + encoder.encodeToString(JSON.writeValueAsBytes(claims));
        var signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(key);
        signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + encoder.encodeToString(jose(signature.sign()));
      } catch (IOException | GeneralSecurityException failure) {
        // The cause is dropped: it could carry key material into a log.
        throw new IllegalStateException("Sign in with Apple client secret unavailable");
      }
    }

    /** Converts a DER ECDSA signature (SEQUENCE of two INTEGERs) to the 64-byte R || S form JOSE uses. */
    static byte[] jose(byte[] der) {
      int offset = 0;
      if (der.length < 8 || der[offset++] != 0x30) throw malformed();
      int length = der[offset++] & 0xff;
      if (length == 0x81) length = der[offset++] & 0xff;
      else if (length > 0x7f) throw malformed();
      if (offset + length != der.length) throw malformed();
      byte[] jose = new byte[64];
      for (int part = 0; part < 2; part++) {
        if (der[offset++] != 0x02) throw malformed();
        int size = der[offset++] & 0xff;
        if (size == 0 || offset + size > der.length) throw malformed();
        int start = offset, end = offset + size;
        while (start < end - 1 && der[start] == 0) start++;
        if (end - start > 32) throw malformed();
        System.arraycopy(der, start, jose, part * 32 + 32 - (end - start), end - start);
        offset = end;
      }
      if (offset != der.length) throw malformed();
      return jose;
    }

    private static IllegalStateException malformed() { return new IllegalStateException("Malformed ECDSA signature"); }

    /** A PKCS #8 PEM P-256 key. Environment values may carry escaped line breaks. */
    static PrivateKey parse(String pem) {
      String body = pem.replace("\\n", "\n").replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
      try {
        var key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
        if (!(key instanceof ECPrivateKey ec) || ec.getParams().getCurve().getField().getFieldSize() != 256)
          throw new IllegalStateException("account.social-sign-in.apple.private-key must be a P-256 key");
        return key;
      } catch (GeneralSecurityException | IllegalArgumentException unreadable) {
        throw new IllegalStateException("account.social-sign-in.apple.private-key must be a PKCS #8 PEM P-256 key");
      }
    }
  }

  @Override public String toString() { return getClass().getSimpleName() + "[redacted]"; }

  static boolean blank(String... values) { return Arrays.stream(values).allMatch(value -> value == null || value.isBlank()); }
  static boolean complete(String... values) { return Arrays.stream(values).noneMatch(value -> value == null || value.isBlank()); }
}

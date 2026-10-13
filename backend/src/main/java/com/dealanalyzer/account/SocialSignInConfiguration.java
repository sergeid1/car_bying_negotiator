package com.dealanalyzer.account;

import com.dealanalyzer.deployment.DeploymentStage;
import java.net.URI;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Google and Apple sign-in settings (ticket 113). A provider with blank settings is not offered. Production-like
 * stages refuse partial settings, a non-HTTPS redirect base and endpoint overrides, which exist only so tests can
 * stub the provider over loopback.
 */
@Component
class SocialSignInConfiguration {
  private static final String PREFIX = "account.social-sign-in.";
  final String redirectBase;
  final Map<SocialProvider, SocialIdentityProvider> providers;

  SocialSignInConfiguration(Environment environment) {
    boolean production = DeploymentStage.from(environment).productionLike();
    redirectBase = strip(setting(environment, "redirect-base",
        environment.getProperty("case.recovery.public-url", "http://localhost:5173/")));
    var configured = new EnumMap<SocialProvider, SocialIdentityProvider>(SocialProvider.class);

    String googleId = setting(environment, "google.client-id", ""), googleSecret = setting(environment, "google.client-secret", "");
    if (SocialIdentityProvider.complete(googleId, googleSecret))
      configured.put(SocialProvider.GOOGLE, new SocialIdentityProvider.Google(googleId.strip(), googleSecret.strip(),
          endpoint(environment, production, "google.authorization-endpoint", SocialIdentityProvider.Google.AUTHORIZATION),
          endpoint(environment, production, "google.token-endpoint", SocialIdentityProvider.Google.TOKEN)));
    else if (production && !SocialIdentityProvider.blank(googleId, googleSecret))
      throw new IllegalStateException("Google sign-in needs both account.social-sign-in.google.client-id and client-secret");

    String appleId = setting(environment, "apple.client-id", ""), team = setting(environment, "apple.team-id", ""),
        keyId = setting(environment, "apple.key-id", ""), key = setting(environment, "apple.private-key", "");
    if (SocialIdentityProvider.complete(appleId, team, keyId, key))
      configured.put(SocialProvider.APPLE, new SocialIdentityProvider.Apple(appleId.strip(), team.strip(), keyId.strip(), key,
          endpoint(environment, production, "apple.authorization-endpoint", SocialIdentityProvider.Apple.AUTHORIZATION),
          endpoint(environment, production, "apple.token-endpoint", SocialIdentityProvider.Apple.TOKEN)));
    else if (production && !SocialIdentityProvider.blank(appleId, team, keyId, key))
      throw new IllegalStateException("Apple sign-in needs account.social-sign-in.apple.client-id, team-id, key-id and private-key");

    if (production && !configured.isEmpty() && !https(redirectBase))
      throw new IllegalStateException("account.social-sign-in.redirect-base must be an HTTPS origin in pilot/production");
    providers = Collections.unmodifiableMap(configured);
  }

  /** The backend callback a provider returns the Buyer to; it must be registered with the provider exactly. */
  String redirectUri(SocialProvider provider) {
    return redirectBase + "/api/v1/account/social-sign-in/" + provider.path() + "/callback";
  }

  private static String setting(Environment environment, String name, String fallback) {
    String value = environment.getProperty(PREFIX + name);
    return value == null || value.isBlank() ? fallback : value;
  }

  private static String endpoint(Environment environment, boolean production, String name, String standard) {
    String value = setting(environment, name, standard).strip();
    if (production && !value.equals(standard))
      throw new IllegalStateException("Social sign-in endpoints cannot be overridden in pilot/production");
    return value;
  }

  private static String strip(String base) {
    String value = base.strip().split("#", 2)[0];
    while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
    return value;
  }

  private static boolean https(String base) {
    try {
      var uri = URI.create(base);
      return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null && uri.getQuery() == null;
    } catch (IllegalArgumentException malformed) { return false; }
  }
}

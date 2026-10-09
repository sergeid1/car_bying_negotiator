package com.dealanalyzer.account;

import com.dealanalyzer.deployment.DeploymentStage;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Exact browser origins and RP ID are operator configuration, never inferred from a request. */
@Component
public class PasskeyConfiguration {
  final String rpId;
  final Set<String> origins;
  final Duration lifetime;

  public PasskeyConfiguration(Environment environment) {
    rpId = environment.getProperty("account.passkeys.rp-id", "localhost");
    origins = Arrays.stream(environment.getProperty("account.passkeys.origins", "http://localhost:5173").split(","))
        .map(String::strip).collect(Collectors.toUnmodifiableSet());
    lifetime = Duration.parse(environment.getProperty("account.passkeys.challenge-lifetime", "PT5M"));
    if (lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(Duration.ofMinutes(5)) > 0)
      throw new IllegalArgumentException("Passkey challenge lifetime must be positive and at most five minutes");
    boolean production = DeploymentStage.from(environment).productionLike();
    if (rpId.isBlank() || rpId.contains(":") || rpId.contains("/") || (production && local(rpId))) throw invalid();
    for (String origin : origins) {
      URI uri;
      try { uri = URI.create(origin); } catch (IllegalArgumentException malformed) { throw invalid(); }
      String host = uri.getHost();
      if (host == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
          || !(uri.getPath() == null || uri.getPath().isEmpty())
          || !(host.equals(rpId) || host.endsWith("." + rpId))
          || !("https".equals(uri.getScheme()) || (!production && local(host) && "http".equals(uri.getScheme())))
          || (production && local(host))) throw invalid();
    }
  }

  private static boolean local(String host) {
    return host.equalsIgnoreCase("localhost") || host.toLowerCase(java.util.Locale.ROOT).endsWith(".localhost")
        || host.equals("127.0.0.1") || host.equals("0.0.0.0") || host.equals("[::1]");
  }
  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Passkeys require a valid RP ID and exact HTTPS origins; localhost is refused in pilot/production");
  }
}

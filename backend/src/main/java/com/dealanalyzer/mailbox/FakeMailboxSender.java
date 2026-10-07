package com.dealanalyzer.mailbox;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Deterministic development and test stand-in for both providers. Its consent page is the redirect URI itself
 * with a code that binds the PKCE challenge, so the whole connect flow runs without a provider. A code ending in
 * {@code .read} grants a read scope as well, which must be refused. Sends are recorded, not delivered; tests
 * choose the next outcomes and can withdraw the grant. Refused under pilot/production.
 */
public class FakeMailboxSender implements MailboxSender {
  public static final String ADDRESS = "connected.buyer@example.com";

  private final MailboxProvider provider;
  private final Deque<Delivery> outcomes = new ArrayDeque<>();
  private final List<Sent> sent = new CopyOnWriteArrayList<>();
  private volatile boolean revoked;
  private final Set<String> revokedRefreshTokens = java.util.concurrent.ConcurrentHashMap.newKeySet();
  private volatile Runnable afterExchange = () -> {};
  private volatile Runnable afterRefresh = () -> {};
  private volatile Runnable atSend = () -> {};
  private volatile boolean withdrawalFails;
  private final java.util.concurrent.atomic.AtomicInteger withdrawals = new java.util.concurrent.atomic.AtomicInteger();

  public record Sent(String from, OutgoingMessage message) {}

  FakeMailboxSender(MailboxProvider provider) { this.provider = provider; }

  @Override public MailboxProvider provider() { return provider; }

  @Override public String authorizationUrl(String state, String codeChallenge, String redirectUri) {
    String code = "fake." + provider.name().toLowerCase() + "." + codeChallenge;
    return redirectUri + (redirectUri.contains("?") ? "&" : "?") + "code=" + encode(code) + "&state=" + encode(state);
  }

  @Override public Grant exchange(String code, String codeVerifier, String redirectUri) {
    String prefix = "fake." + provider.name().toLowerCase() + ".";
    if (code == null || !code.startsWith(prefix) || codeVerifier == null) throw new MailboxFailure(MailboxFailure.Kind.REFUSED);
    boolean read = code.endsWith(".read");
    String challenge = code.substring(prefix.length(), code.length() - (read ? 5 : 0));
    if (!challenge.equals(Pkce.challenge(codeVerifier))) throw new MailboxFailure(MailboxFailure.Kind.REFUSED);
    revoked = false;
    var scopes = new java.util.HashSet<>(Set.of(provider.requestedScope().split(" ")));
    if (read) scopes.add(provider == MailboxProvider.GOOGLE ? "https://www.googleapis.com/auth/gmail.readonly" : "Mail.Read");
    var grant = new Grant("fake-access-" + UUID.randomUUID(), "fake-refresh-" + UUID.randomUUID(),
        Instant.now().plus(Duration.ofHours(1)), Set.copyOf(scopes), ADDRESS);
    afterExchange.run();
    return grant;
  }

  @Override public AccessToken refresh(String refreshToken) {
    if (revoked || revokedRefreshTokens.contains(refreshToken)) throw new MailboxFailure(MailboxFailure.Kind.REVOKED);
    var token = new AccessToken("fake-access-" + UUID.randomUUID(), Instant.now().plus(Duration.ofHours(1)));
    afterRefresh.run();
    return token;
  }

  @Override public Delivery send(String accessToken, String from, OutgoingMessage message) {
    atSend.run();
    Delivery outcome;
    synchronized (outcomes) { outcome = outcomes.isEmpty() ? Delivery.SENT : outcomes.removeFirst(); }
    if (outcome == Delivery.SENT || outcome == Delivery.UNKNOWN) sent.add(new Sent(from, message));
    return outcome;
  }

  @Override public void revoke(String refreshToken) {
    withdrawals.incrementAndGet();
    if (withdrawalFails) throw new MailboxFailure(MailboxFailure.Kind.UNAVAILABLE);
    revokedRefreshTokens.add(refreshToken);
  }

  /** Controlled boundaries for deterministic lifecycle tests; no tokens are exposed. */
  public void afterExchange(Runnable action) { afterExchange = action; }
  public void afterRefresh(Runnable action) { afterRefresh = action; }
  public void atSend(Runnable action) { atSend = action; }
  public void withdrawalFails(boolean value) { withdrawalFails = value; }
  public int withdrawals() { return withdrawals.get(); }

  /** The next sends end with these outcomes, in order; afterwards sends succeed. */
  public void nextOutcomes(Delivery... next) { synchronized (outcomes) { outcomes.addAll(List.of(next)); } }

  /** The provider withdraws the grant: the next token refresh fails as revoked. */
  public void withdrawGrant() { revoked = true; }

  /** Messages the provider accepted, or may have accepted for an unclear outcome. */
  public List<Sent> sent() { return List.copyOf(sent); }

  public void reset() {
    synchronized (outcomes) { outcomes.clear(); }
    sent.clear(); revoked = false; revokedRefreshTokens.clear(); withdrawals.set(0); withdrawalFails = false;
    afterExchange = () -> {}; afterRefresh = () -> {}; atSend = () -> {};
  }

  private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

  @Configuration
  @ConditionalOnProperty(name = "mailbox.sender", havingValue = "fake", matchIfMissing = true)
  static class Senders {
    @Bean FakeMailboxSender fakeGmail() { return new FakeMailboxSender(MailboxProvider.GOOGLE); }
    @Bean FakeMailboxSender fakeOutlook() { return new FakeMailboxSender(MailboxProvider.MICROSOFT); }
  }
}

package com.dealanalyzer.mailbox;

import java.time.Instant;
import java.util.Set;

/**
 * Replaceable boundary to one mailbox provider's OAuth 2.0 authorization and send-only API (spec 2.1, AGT-12).
 * Implementations never read the Buyer's mailbox and never log tokens, addresses, subjects, bodies or provider
 * payloads; failures carry a kind only. Selected by {@code mailbox.sender}: {@code fake} (development and tests,
 * refused under pilot/production by {@link MailboxProductionGuard}), {@code live} (the Gmail and Microsoft Graph
 * adapters) or {@code none}.
 */
public interface MailboxSender {
  MailboxProvider provider();

  /** The provider's consent page for an authorization code request with PKCE (S256) and the given state. */
  String authorizationUrl(String state, String codeChallenge, String redirectUri);

  /** Redeems the authorization code with its PKCE verifier. Throws {@link MailboxFailure}. */
  Grant exchange(String code, String codeVerifier, String redirectUri);

  /** A fresh access token. Throws {@link MailboxFailure}, of kind {@code REVOKED} when the grant was withdrawn. */
  AccessToken refresh(String refreshToken);

  /** Sends one plain-text message from the connected address. Never retried by the implementation. */
  Delivery send(String accessToken, String from, OutgoingMessage message);

  /** Asks the provider to withdraw the grant, where it supports that. Best effort; failures are ignored. */
  default void revoke(String refreshToken) {}

  record Grant(String accessToken, String refreshToken, Instant accessExpiresAt, Set<String> scopes, String address) {
    @Override public String toString() { return "Grant[redacted]"; }
  }

  record AccessToken(String token, Instant expiresAt) {
    @Override public String toString() { return "AccessToken[redacted]"; }
  }

  record OutgoingMessage(String to, String subject, String body) {
    @Override public String toString() { return "OutgoingMessage[redacted]"; }
  }

  /**
   * What the provider's answer shows. {@code SENT}: accepted. {@code UNKNOWN}: a timeout, lost connection or
   * server error, so the message may or may not have gone. Every other outcome means it was not sent.
   */
  enum Delivery { SENT, UNKNOWN, RATE_LIMITED, AUTH_FAILED, REJECTED }

  /** An authorization or token request that failed. The message names the kind only. */
  final class MailboxFailure extends RuntimeException {
    public enum Kind { REVOKED, REFUSED, UNAVAILABLE }
    private final Kind kind;
    public MailboxFailure(Kind kind) { super(kind.name(), null, false, false); this.kind = kind; }
    public Kind kind() { return kind; }
  }
}

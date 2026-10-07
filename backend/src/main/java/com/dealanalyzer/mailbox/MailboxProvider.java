package com.dealanalyzer.mailbox;

import java.util.Set;

/**
 * The two mailbox providers a Buyer can connect (spec 2.1, AGT-12). Each requests only its send permission and
 * the identity scopes that name the sending address; a grant with any other scope, such as mail read access, is
 * refused.
 */
public enum MailboxProvider {
  GOOGLE("Gmail", "https://www.googleapis.com/auth/gmail.send", "openid email https://www.googleapis.com/auth/gmail.send",
      Set.of("openid", "email", "https://www.googleapis.com/auth/userinfo.email")),
  MICROSOFT("Outlook", "Mail.Send", "openid email offline_access https://graph.microsoft.com/Mail.Send",
      Set.of("openid", "email", "offline_access", "profile", "User.Read"));

  private final String label;
  private final String sendScope;
  private final String requestedScope;
  private final Set<String> identityScopes;

  MailboxProvider(String label, String sendScope, String requestedScope, Set<String> identityScopes) {
    this.label = label; this.sendScope = sendScope; this.requestedScope = requestedScope; this.identityScopes = identityScopes;
  }

  /** The name a Buyer knows the service by. */
  public String label() { return label; }

  /** The scope string sent with the authorization request. */
  public String requestedScope() { return requestedScope; }

  /** True when the granted scopes include the send permission. */
  boolean grantsSend(Set<String> granted) { return granted.stream().map(this::normalize).anyMatch(sendScope::equals); }

  /**
   * True when every granted scope is the send permission or an identity scope. Anything else (mail read,
   * modify, metadata, settings or full mailbox access) makes the grant unacceptable.
   */
  boolean sendOnly(Set<String> granted) {
    return granted.stream().map(this::normalize).allMatch(scope -> scope.equals(sendScope) || identityScopes.contains(scope));
  }

  /** Microsoft reports Graph scopes with or without the resource prefix. */
  private String normalize(String scope) {
    return this == MICROSOFT && scope.regionMatches(true, 0, "https://graph.microsoft.com/", 0, 28) ? scope.substring(28) : scope;
  }
}

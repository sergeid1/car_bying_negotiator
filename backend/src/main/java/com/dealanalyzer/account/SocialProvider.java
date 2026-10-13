package com.dealanalyzer.account;

/** The identity providers a Buyer can sign in with (ticket 113). Facebook is out of scope. */
public enum SocialProvider {
  GOOGLE("Google", "google"),
  APPLE("Apple", "apple");

  private final String label;
  private final String path;

  SocialProvider(String label, String path) { this.label = label; this.path = path; }

  /** The name a Buyer knows the provider by. */
  public String label() { return label; }

  /** The provider's segment in its callback route. */
  String path() { return path; }
}

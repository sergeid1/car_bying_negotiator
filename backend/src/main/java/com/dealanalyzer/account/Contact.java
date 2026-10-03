package com.dealanalyzer.account;

/** The Buyer's contact details. Stored once, encrypted, and returned only to the signed-in Buyer. */
public record Contact(String email, String firstName, String lastName, String phone) {
  /** Excludes every field from toString so a stray log statement cannot print contact details. */
  @Override public String toString() { return "Contact[redacted]"; }
}

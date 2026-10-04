package com.dealanalyzer.notification;

/** Event types, never event content. Account security is mandatory. */
public enum NotificationType {
  DEALER_REPLY, DRAFT_NEEDS_APPROVAL, MANDATE_EXPIRING, APPOINTMENT, ACCOUNT_SECURITY;

  public boolean mandatory() { return this == ACCOUNT_SECURITY; }
}

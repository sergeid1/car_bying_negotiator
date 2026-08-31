package com.dealanalyzer.vehicle;

public record ResolveIdentityConflictRequest(Resolution resolution) {
  public enum Resolution { KEEP_CONFIRMED_VEHICLE, USE_LISTING_VEHICLE }
}

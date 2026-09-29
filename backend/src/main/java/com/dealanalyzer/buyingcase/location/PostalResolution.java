package com.dealanalyzer.buyingcase.location;

import java.util.List;

/**
 * Where a postal code is, as far as the reference dataset can say. The Buyer's value is
 * deliberately absent so a resolution can be reported without echoing what was entered.
 */
public record PostalResolution(Status status, List<String> candidates) {
  public enum Status {
    /** Exactly one jurisdiction contains the postal code. */
    RESOLVED,
    /** The dataset does not contain the postal code; PO-box-only ZIPs land here. */
    UNRECOGNIZED,
    /** The postal code spans a state line, so its tax jurisdiction cannot be inferred. */
    CROSSES_STATE_LINE
  }

  public PostalResolution(Status status, List<String> candidates) {
    this.status = status;
    this.candidates = List.copyOf(candidates);
  }

  /** The single containing jurisdiction. Only a RESOLVED resolution has one. */
  public String jurisdiction() {
    if (status != Status.RESOLVED) {
      throw new IllegalStateException("A " + status + " postal code has no single jurisdiction");
    }
    return candidates.get(0);
  }
}

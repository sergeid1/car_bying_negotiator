package com.dealanalyzer.buyingcase;

import java.util.List;

/**
 * The Buyer's location cannot open a case. Thrown before anything is stored, so a refused
 * location leaves no personal data behind. Carries no submitted value, only the field, a stable
 * reason, and the jurisdictions that would resolve it.
 */
public class UnsupportedLocationException extends RuntimeException {
  public enum Reason {
    /** The postal code is not in the reference dataset, including PO-box-only ZIPs. */
    UNRECOGNIZED_POSTAL_CODE,
    /** The postal code spans a state line and the Buyer has not said which state is theirs. */
    POSTAL_CODE_CROSSES_STATE_LINE,
    /** The jurisdiction is real but has no policy pack yet. */
    JURISDICTION_NOT_SUPPORTED
  }

  private final String field;
  private final Reason reason;
  private final List<String> candidates;

  public UnsupportedLocationException(
      String field, Reason reason, String explanation, List<String> candidates) {
    super(explanation);
    this.field = field;
    this.reason = reason;
    this.candidates = List.copyOf(candidates);
  }

  public String field() {
    return field;
  }

  public Reason reason() {
    return reason;
  }

  /** For a crossing postal code, the states it touches; otherwise the supported jurisdictions. */
  public List<String> candidates() {
    return candidates;
  }
}

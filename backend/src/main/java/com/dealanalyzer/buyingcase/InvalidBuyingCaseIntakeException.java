package com.dealanalyzer.buyingcase;

/**
 * A Buyer-correctable intake failure. It names the field and the required correction and never
 * carries the submitted value, so the message can be returned and logged safely.
 */
public class InvalidBuyingCaseIntakeException extends RuntimeException {
  private final String field;

  public InvalidBuyingCaseIntakeException(String field, String correction) {
    super(correction);
    this.field = field;
  }

  public String field() {
    return field;
  }
}

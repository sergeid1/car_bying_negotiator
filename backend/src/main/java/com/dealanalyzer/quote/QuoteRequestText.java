package com.dealanalyzer.quote;

/** Only case-backed vehicle references are allowed in the closed outbound language. */
public final class QuoteRequestText {
  private QuoteRequestText() {}
  public static String render(String vin, String listing) {
    if (vin == null || !vin.matches("[A-HJ-NPR-Z0-9]{17}"))
      throw new IllegalArgumentException("Confirm the Vehicle VIN before requesting a quote.");
    if (listing == null || listing.length() > 1000 || !listing.matches("https?://[A-Za-z0-9.-]+(?::[0-9]+)?/[A-Za-z0-9_./?=&%+~:#-]*"))
      throw new IllegalArgumentException("A supported listing URL is required before requesting a quote.");
    return "Could you provide an itemized out-the-door quote for VIN " + vin + " (listing: " + listing
        + "), including the selling price, taxes, all fees and all add-ons?";
  }
}

package com.dealanalyzer.quote;

/** Only case-backed vehicle references are allowed in the closed outbound language. */
public final class QuoteRequestText {
  private QuoteRequestText() {}
  /** A listing saved from the Buyer's own capture of the ad has no link, so the VIN alone names the car. */
  public static String render(String vin, String listing) {
    return render(vin, listing, "en");
  }

  /** The request in the dealer's email language ("en" or "es"); the VIN and listing link are the same in both. */
  public static String render(String vin, String listing, String language) {
    if (vin == null || !vin.matches("[A-HJ-NPR-Z0-9]{17}"))
      throw new IllegalArgumentException("Confirm the Vehicle VIN before requesting a quote.");
    boolean spanish = "es".equals(language);
    if (listing == null)
      return spanish
          ? "¿Podría enviarme una cotización detallada del precio total final (out-the-door) para el VIN " + vin
              + ", que incluya el precio de venta, los impuestos, todos los cargos y todos los productos adicionales?"
          : "Could you provide an itemized out-the-door quote for VIN " + vin
          + ", including the selling price, taxes, all fees and all add-ons?";
    if (listing.length() > 1000 || !listing.matches("https?://[A-Za-z0-9.-]+(?::[0-9]+)?/[A-Za-z0-9_./?=&%+~:#-]*"))
      throw new IllegalArgumentException("A supported listing URL is required before requesting a quote.");
    if (spanish)
      return "¿Podría enviarme una cotización detallada del precio total final (out-the-door) para el VIN " + vin
          + " (anuncio: " + listing + "), que incluya el precio de venta, los impuestos, todos los cargos y todos los productos adicionales?";
    return "Could you provide an itemized out-the-door quote for VIN " + vin + " (listing: " + listing
        + "), including the selling price, taxes, all fees and all add-ons?";
  }
}

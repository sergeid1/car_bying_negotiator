package com.dealanalyzer.journey.vin;

/**
 * Replaceable VIN decoder (SHOP-02). Callers pass a VIN that already passed
 * {@link com.dealanalyzer.vehicle.Vin#validated}; an invalid VIN never reaches a decoder.
 * Selected by {@code vin-decoder.provider}: {@code fake} (the default) or {@code nhtsa-vpic}.
 */
public interface VinDecoder {
  /**
   * Decodes one VIN.
   *
   * @throws VinDecoderUnavailableException when the decoder did not answer; nothing may be guessed in its place
   */
  VinDecoding decode(String vin);
}

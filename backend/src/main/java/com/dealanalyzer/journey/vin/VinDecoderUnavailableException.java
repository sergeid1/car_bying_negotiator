package com.dealanalyzer.journey.vin;

/** The decoder did not answer. The message never contains the VIN. */
public class VinDecoderUnavailableException extends RuntimeException {
  public VinDecoderUnavailableException(String reason) { super(reason, null, false, false); }
}

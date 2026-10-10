package com.dealanalyzer.journey.vin;

/** The Buyer-facing correction for a VIN that fails the checksum, shared by every VIN the Buyer enters. */
public final class VinChecksum {
  public static final String MESSAGE = "This VIN fails the VIN checksum. Enter all 17 characters exactly as "
      + "shown on the car or its paperwork; VINs never use the letters I, O or Q.";

  private VinChecksum() {}
}

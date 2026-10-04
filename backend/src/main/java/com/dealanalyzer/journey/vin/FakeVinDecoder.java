package com.dealanalyzer.journey.vin;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Deterministic decoder for development and the main test suite; it never calls a network endpoint
 * and is refused under pilot/production. A VIN outside its table is undecodable.
 */
@Component
@ConditionalOnProperty(name = "vin-decoder.provider", havingValue = "fake", matchIfMissing = true)
public class FakeVinDecoder implements VinDecoder {
  public static final String SOURCE = "Development VIN decoder (fixed sample data)";
  public static final String VERSION = "fake-vin-decoder-v1";
  /** A checksum-valid VIN for which the fake behaves as if the decoder were not answering. */
  public static final String UNAVAILABLE_VIN = "1N4BL4BV0LC000007";

  private static final Map<String, Map<VehicleAttribute, String>> VEHICLES = Map.of(
      "2T3P1RFV5MW000001", Map.of(VehicleAttribute.MODEL_YEAR, "2021", VehicleAttribute.MAKE, "Toyota",
          VehicleAttribute.MODEL, "RAV4", VehicleAttribute.TRIM, "XLE", VehicleAttribute.BODY, "Sport utility vehicle",
          VehicleAttribute.DRIVETRAIN, "All-wheel drive", VehicleAttribute.ENGINE, "2.5 L 4-cylinder gasoline",
          VehicleAttribute.TRANSMISSION, "8-speed automatic"),
      "5J6RW2H52ML000002", Map.of(VehicleAttribute.MODEL_YEAR, "2021", VehicleAttribute.MAKE, "Honda",
          VehicleAttribute.MODEL, "CR-V", VehicleAttribute.TRIM, "EX", VehicleAttribute.BODY, "Sport utility vehicle",
          VehicleAttribute.DRIVETRAIN, "All-wheel drive", VehicleAttribute.ENGINE, "1.5 L 4-cylinder turbocharged gasoline",
          VehicleAttribute.TRANSMISSION, "Continuously variable"),
      "1GCUYHED0KZ000003", Map.of(VehicleAttribute.MODEL_YEAR, "2019", VehicleAttribute.MAKE, "Chevrolet",
          VehicleAttribute.MODEL, "Silverado 1500", VehicleAttribute.TRIM, "High Country", VehicleAttribute.BODY, "Pickup",
          VehicleAttribute.DRIVETRAIN, "Four-wheel drive", VehicleAttribute.ENGINE, "6.2 L 8-cylinder gasoline",
          VehicleAttribute.TRANSMISSION, "10-speed automatic"),
      // A partial decode: trim, engine and transmission are not supplied and stay absent.
      "JTMRWRFV3LD000004", Map.of(VehicleAttribute.MODEL_YEAR, "2020", VehicleAttribute.MAKE, "Toyota",
          VehicleAttribute.MODEL, "RAV4", VehicleAttribute.BODY, "Sport utility vehicle",
          VehicleAttribute.DRIVETRAIN, "All-wheel drive"));

  private volatile boolean unavailable;

  @Override public VinDecoding decode(String vin) {
    if (unavailable || UNAVAILABLE_VIN.equals(vin)) throw new VinDecoderUnavailableException("Fake decoder set unavailable");
    return VinDecoding.of(SOURCE, VERSION, VEHICLES.getOrDefault(vin, Map.of()));
  }

  /** Makes every decode fail as unavailable, for tests of the stated-unavailability path. */
  public void unavailable(boolean unavailable) { this.unavailable = unavailable; }
}

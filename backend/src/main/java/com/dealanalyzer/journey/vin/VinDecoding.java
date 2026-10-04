package com.dealanalyzer.journey.vin;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * What a decoder answered for one VIN. An attribute the decoder did not supply is absent, never
 * filled in; a VIN for which the decoder supplied no year, make or model is {@code UNDECODABLE}.
 */
public record VinDecoding(Outcome outcome, String source, String decoderVersion, Map<VehicleAttribute, String> attributes) {
  public enum Outcome { DECODED, UNDECODABLE }

  public VinDecoding {
    var copy = new EnumMap<VehicleAttribute, String>(VehicleAttribute.class);
    attributes.forEach((attribute, value) -> {
      if (value != null && !value.isBlank()) copy.put(attribute, value.strip());
    });
    if (outcome == Outcome.UNDECODABLE) copy.clear();
    attributes = Collections.unmodifiableMap(copy);
  }

  /** Decoded when the decoder supplied any of year, make or model; otherwise undecodable. */
  public static VinDecoding of(String source, String decoderVersion, Map<VehicleAttribute, String> attributes) {
    boolean identified = java.util.stream.Stream.of(VehicleAttribute.MODEL_YEAR, VehicleAttribute.MAKE, VehicleAttribute.MODEL)
        .map(attributes::get).anyMatch(value -> value != null && !value.isBlank());
    return new VinDecoding(identified ? Outcome.DECODED : Outcome.UNDECODABLE, source, decoderVersion, attributes);
  }
}

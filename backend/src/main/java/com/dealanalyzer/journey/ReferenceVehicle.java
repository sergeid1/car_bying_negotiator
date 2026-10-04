package com.dealanalyzer.journey;

import com.dealanalyzer.journey.vin.VehicleAttribute;
import com.dealanalyzer.journey.vin.VinDecoding;
import java.time.Instant;
import java.util.List;

/**
 * A VIN the Buyer likes, as decoded when it was added, in one Shopping Brief version. Each attribute
 * carries the decoder's source and version and whether the Buyer marked it as mattering. Only
 * {@link #relevantAttributes() relevant} attributes refine matching. Read the current version's
 * vehicles through {@link JourneyService#referenceVehicles(String, String)}.
 */
public record ReferenceVehicle(String referenceId, String vin, VinDecoding.Outcome outcome, String source,
    String decoderVersion, Instant decodedAt, List<Attribute> attributes) {
  public record Attribute(VehicleAttribute attribute, String label, String value, String source, String decoderVersion,
      boolean relevant) {}

  public ReferenceVehicle { attributes = List.copyOf(attributes); }

  /** The attributes the Buyer marked as mattering; the only ones later matching may use. */
  public List<Attribute> relevantAttributes() { return attributes.stream().filter(Attribute::relevant).toList(); }
}

package com.dealanalyzer.vehicle.safety;

import java.time.Instant;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "vehicle-safety.provider", havingValue = "fake", matchIfMissing = true)
public class FakeVehicleSafetyProvider implements VehicleSafetyProvider {
  @Override public Facts retrieve(Vehicle vehicle) {
    return new Facts("AVAILABLE", "Development sample — not NHTSA data", Instant.now(), vehicle,
        List.of(new Recall("SAMPLE-116", "Sample component", "Development sample recall.",
            "Sample consequence.", "Ask for the repair record.", "2026-01-01")),
        List.of(new Rating("sample", "Sample variant", "4", "4", "5", "4", "0.15")));
  }
}

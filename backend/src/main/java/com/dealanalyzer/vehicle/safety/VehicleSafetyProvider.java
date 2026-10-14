package com.dealanalyzer.vehicle.safety;

import java.time.Instant;
import java.util.List;

/** This boundary accepts only model identity; never a VIN or Buyer identity. */
public interface VehicleSafetyProvider {
  Facts retrieve(Vehicle vehicle);
  record Vehicle(int year, String make, String model) {
    public Vehicle {
      if (year < 1981 || year > 2100 || make == null || model == null
          || make.isBlank() || model.isBlank() || make.length() > 100 || model.length() > 100)
        throw new IllegalArgumentException("Year, make and model are required");
      make = make.strip(); model = model.strip();
    }
  }
  record Recall(String campaignNumber, String component, String summary, String consequence,
      String remedy, String reportDate) {}
  record Rating(String vehicleId, String name, String overall, String frontal, String side,
      String rollover, String rolloverRisk) {}
  record Facts(String status, String source, Instant retrievedAt, Vehicle vehicle,
      List<Recall> recalls, List<Rating> ratings) {
    public Facts { recalls = List.copyOf(recalls); ratings = List.copyOf(ratings); }
  }
}

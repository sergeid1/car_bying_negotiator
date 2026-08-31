package com.dealanalyzer.vehicle;

public record EstablishVehicleRequest(
    String vin, ManualVehicleFacts manualFacts, String listingUrl, boolean confirmed) {

  public record ManualVehicleFacts(
      Integer year, String make, String model, String trim, Long mileage) {}
}

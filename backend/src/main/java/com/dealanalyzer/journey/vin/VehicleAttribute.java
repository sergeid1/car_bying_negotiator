package com.dealanalyzer.journey.vin;

/** The decoded attributes a Reference Vehicle can carry, in display order. */
public enum VehicleAttribute {
  MODEL_YEAR("Model year"), MAKE("Make"), MODEL("Model"), TRIM("Trim"), BODY("Body"), DRIVETRAIN("Drivetrain"),
  ENGINE("Engine"), TRANSMISSION("Transmission");

  private final String label;

  VehicleAttribute(String label) { this.label = label; }

  public String label() { return label; }
}

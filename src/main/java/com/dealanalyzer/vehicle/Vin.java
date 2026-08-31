package com.dealanalyzer.vehicle;

import java.util.Locale;

final class Vin {
  private static final int[] WEIGHTS = {8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2};

  private Vin() {}

  static String validated(String value) {
    String vin = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
    if (vin.length() != 17 || !vin.matches("[A-HJ-NPR-Z0-9]{17}") || !checksumMatches(vin)) {
      throw new InvalidVehicleEvidenceException(
          "VIN checksum is invalid; check the VIN and try again, or use manual vehicle facts.");
    }
    return vin;
  }

  private static boolean checksumMatches(String vin) {
    int sum = 0;
    for (int index = 0; index < vin.length(); index++) {
      sum += transliterate(vin.charAt(index)) * WEIGHTS[index];
    }
    int remainder = sum % 11;
    char expected = remainder == 10 ? 'X' : Character.forDigit(remainder, 10);
    return vin.charAt(8) == expected;
  }

  private static int transliterate(char character) {
    if (Character.isDigit(character)) {
      return character - '0';
    }
    return switch (character) {
      case 'A', 'J' -> 1;
      case 'B', 'K', 'S' -> 2;
      case 'C', 'L', 'T' -> 3;
      case 'D', 'M', 'U' -> 4;
      case 'E', 'N', 'V' -> 5;
      case 'F', 'W' -> 6;
      case 'G', 'P', 'X' -> 7;
      case 'H', 'Y' -> 8;
      case 'R', 'Z' -> 9;
      default -> throw new IllegalArgumentException("Unsupported VIN character");
    };
  }
}

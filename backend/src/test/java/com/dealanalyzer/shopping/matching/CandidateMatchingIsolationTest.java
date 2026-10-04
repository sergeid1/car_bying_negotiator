package com.dealanalyzer.shopping.matching;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Candidate matching orders vehicles for the Buyer alone (credo 3). Like the acquisition channel
 * isolation, this proves the matching package has no path to commercial, channel, payment or
 * dealer-identity data: it may import only the brief, Reference Vehicles and the inventory search.
 */
class CandidateMatchingIsolationTest {
  private static final Path MATCHING = Path.of("src/main/java/com/dealanalyzer/shopping/matching");
  private static final Pattern FORBIDDEN = Pattern.compile("(?i)dealer|seller|commercial|channel|acquisition|referral"
      + "|payment|commission|sponsor|promot|placement|incentive|holdback|lead.?fee|partner|advertiser|billing|stripe"
      + "|relay|quote|negotiat|mandate|buyingcase|pilot");
  private static final Set<String> ALLOWED_IMPORTS = Set.of(
      "com.dealanalyzer.journey.JourneyService", "com.dealanalyzer.journey.ReferenceVehicle",
      "com.dealanalyzer.journey.ShoppingBrief", "com.dealanalyzer.journey.vin.VehicleAttribute",
      "com.dealanalyzer.shopping.inventory.InventoryListing", "com.dealanalyzer.shopping.inventory.InventorySearchAdapter",
      "com.dealanalyzer.shopping.inventory.InventorySearchService", "org.springframework.stereotype.Service");
  private static final Pattern IMPORT = Pattern.compile("(?m)^import (?:static )?([\\w.]+?)(?:\\.\\*)?;");

  private static List<Path> sources() throws IOException {
    try (Stream<Path> files = Files.walk(MATCHING)) {
      return files.filter(file -> file.toString().endsWith(".java")).sorted().toList();
    }
  }

  @Test void matchingSourcesNameNoCommercialChannelPaymentOrDealerData() throws IOException {
    assertThat(sources()).extracting(file -> file.getFileName().toString())
        .contains("CandidateMatcher.java", "ListingEvidence.java", "CandidateMatchingService.java");
    for (Path file : sources()) {
      var found = FORBIDDEN.matcher(Files.readString(file, StandardCharsets.UTF_8));
      if (found.find()) throw new AssertionError(file + " names " + found.group());
    }
  }

  @Test void matchingImportsOnlyTheBriefReferenceVehiclesAndInventorySearch() throws IOException {
    for (Path file : sources()) {
      var imports = IMPORT.matcher(Files.readString(file, StandardCharsets.UTF_8));
      while (imports.find()) {
        String name = imports.group(1);
        if (name.startsWith("java.") || name.startsWith("com.dealanalyzer.shopping.matching.")) continue;
        assertThat(ALLOWED_IMPORTS).as(file + " imports " + name).contains(name);
      }
      // Same-package or fully qualified references cannot bypass the import rule.
      assertThat(Files.readString(file, StandardCharsets.UTF_8).replaceAll("(?m)^import .*$", ""))
          .as(file.toString()).doesNotContainPattern("com\\.dealanalyzer\\.(?!shopping\\.matching)");
    }
  }

}

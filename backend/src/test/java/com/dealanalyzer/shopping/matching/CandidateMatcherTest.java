package com.dealanalyzer.shopping.matching;

import static org.assertj.core.api.Assertions.*;

import com.dealanalyzer.journey.ReferenceVehicle;
import com.dealanalyzer.journey.ShoppingBrief;
import com.dealanalyzer.journey.vin.VehicleAttribute;
import com.dealanalyzer.journey.vin.VinDecoding;
import com.dealanalyzer.shopping.inventory.InventoryListing;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class CandidateMatcherTest {
  static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
  static final ShoppingBrief.Choice AWD = new ShoppingBrief.Choice("all-wheel-drive", "All-wheel drive");
  static final ShoppingBrief.Choice SEATS = new ShoppingBrief.Choice("heated-seats", "Heated seats");
  static final ShoppingBrief.Choice CARPLAY = new ShoppingBrief.Choice("apple-carplay", "Apple CarPlay");

  static ShoppingBrief brief() {
    return new ShoppingBrief("journey", 3, "reference.v1", 2020, 2024, new ShoppingBrief.Choice("honda", "Honda"),
        new ShoppingBrief.Choice("honda-civic", "Civic"),
        List.of(new ShoppingBrief.Choice("honda-civic-ex", "EX"), new ShoppingBrief.Choice("honda-civic-lx", "LX")),
        60000, 3200000, "USD", "27601", "US-NC", 50, List.of(AWD), List.of(SEATS, CARPLAY), NOW);
  }

  static InventoryListing listing(String id, String vin, Integer year, String trim, Long mileage, List<String> features,
      Integer distance, Long price, Instant observed) {
    return listing(id, new InventoryListing.Dealer("dealer-1", "Example Motors", "Raleigh", "US-NC"), vin,
        new InventoryListing.Facts(year, "Honda", "Civic", trim, mileage, features, distance), price, observed);
  }

  static InventoryListing listing(String id, InventoryListing.Dealer dealer, String vin, InventoryListing.Facts facts,
      Long price, Instant observed) {
    return new InventoryListing(id, dealer, vin, facts, price, price == null ? null : "USD", "SYNTHETIC_INVENTORY",
        "SYNTHETIC_DISPLAY_AND_CACHE_PERMITTED", NOW, observed, null, true);
  }

  static ReferenceVehicle reference(String vin, String trim, boolean trimRelevant) {
    return new ReferenceVehicle("ref-" + vin, vin, VinDecoding.Outcome.DECODED, "fake", "fake.v1", NOW, List.of(
        new ReferenceVehicle.Attribute(VehicleAttribute.TRIM, "Trim", trim, "fake", "fake.v1", trimRelevant),
        new ReferenceVehicle.Attribute(VehicleAttribute.DRIVETRAIN, "Drivetrain", "Front-wheel drive", "fake", "fake.v1", trimRelevant),
        new ReferenceVehicle.Attribute(VehicleAttribute.BODY, "Body", "Sedan", "fake", "fake.v1", false)));
  }

  static final List<String> ALL = List.of("all-wheel-drive", "heated-seats", "apple-carplay");

  @Test void eachCandidateListsMatchedMissedAndUnconfirmedCriteriaInPlainWords() {
    var only = CandidateMatcher.match(brief(), List.of(reference("2HGFE2F55NH000008", "EX", true)), List.of(
        listing("a", "2HGFE2F55NH000008", 2022, "EX", 30000L, List.of("all-wheel-drive", "heated-seats"), 12, 3000050L, NOW)));
    var candidate = only.candidates().getFirst();
    assertThat(candidate.title()).isEqualTo("2022 Honda Civic EX");
    assertThat(candidate.matched()).extracting(CandidateVehicle.Reason::text).containsExactly(
        "Model year 2022 is within 2020 to 2024.", "It is a Honda Civic.", "Trim EX is one of your trims.",
        "30,000 miles is within your 60,000-mile maximum.", "Advertised price $30,000.50 is within your $32,000 ceiling.",
        "12 miles away, within your 50-mile area.", "Lists all-wheel drive.", "Lists heated seats, a nice-to-have.",
        "Matches your reference trim (EX).");
    assertThat(candidate.missed()).extracting(CandidateVehicle.Reason::text)
        .containsExactly("Does not list Apple CarPlay, a nice-to-have.");
    assertThat(candidate.unconfirmed()).extracting(CandidateVehicle.Reason::text)
        .containsExactly("The listing does not state its drivetrain, so your reference drivetrain (Front-wheel drive) is unconfirmed.");
    assertThat(candidate.snapshot().source()).isEqualTo("SYNTHETIC_INVENTORY");
    assertThat(candidate.snapshot().retrievedAt()).isEqualTo(NOW);
    assertThat(candidate.snapshot().staleness()).isEqualTo(InventoryListing.Staleness.CURRENT);
  }

  @Test void aStatedMustHaveMissExcludesTheListingWhileAnUnstatedOneOnlyMovesItDown() {
    var results = CandidateMatcher.match(brief(), List.of(), List.of(
        listing("over-miles", "2HGFE2F55NH000001", 2022, "EX", 70000L, ALL, 5, 2500000L, NOW),
        listing("wrong-trim", "2HGFE2F55NH000002", 2022, "Touring", 1000L, ALL, 5, 2500000L, NOW),
        listing("old", "2HGFE2F55NH000003", 2018, "EX", 1000L, ALL, 5, 2500000L, NOW),
        listing("far", "2HGFE2F55NH000004", 2022, "EX", 1000L, ALL, 80, 2500000L, NOW),
        listing("pricey", "2HGFE2F55NH000006", 2022, "EX", 1000L, ALL, 5, 3200001L, NOW),
        listing("no-awd-stated", "2HGFE2F55NH000007", 2022, "EX", 1000L, List.of("heated-seats", "apple-carplay"), 5, 2500000L, NOW),
        listing("good", "2HGFE2F55NH000009", 2022, "EX", 50000L, ALL, 40, 3100000L, NOW)));
    assertThat(results.candidates()).extracting(CandidateVehicle::listingId).containsExactly("good", "no-awd-stated");
    assertThat(results.candidates().get(1).unconfirmed()).extracting(CandidateVehicle.Reason::text)
        .containsExactly("The listing does not list all-wheel drive, a must-have.");
    assertThat(results.excluded()).extracting(CandidateVehicle.Excluded::listingId)
        .containsExactlyInAnyOrder("over-miles", "wrong-trim", "old", "far", "pricey");
    assertThat(results.excluded()).flatExtracting(CandidateVehicle.Excluded::missed).extracting(CandidateVehicle.Reason::text)
        .contains("70,000 miles is over your 60,000-mile maximum.", "Trim Touring is not one of your trims (EX, LX).",
            "Model year 2018 is outside 2020 to 2024.", "80 miles away, outside your 50-mile area.",
            "Advertised price $32,000.01 is over your $32,000 ceiling.");
  }

  @Test void niceToHavesThenReferenceDetailsThenEvidenceOrderTheRest() {
    var refs = List.of(reference("2HGFE2F55NH000008", "EX", true), reference("2HGFE2F5XNH000005", "ex", true));
    var results = CandidateMatcher.match(brief(), refs, List.of(
        listing("lx-both", "2HGFE2F55NH000011", 2022, "LX", 50000L, ALL, 40, 3100000L, NOW),
        listing("ex-one", "2HGFE2F55NH000012", 2022, "EX", 1000L, List.of("all-wheel-drive", "heated-seats"), 5, 2000000L, NOW),
        listing("lx-one", "2HGFE2F55NH000013", 2022, "LX", 1000L, List.of("all-wheel-drive", "heated-seats"), 5, 2000000L, NOW),
        listing("ex-one-stale", "2HGFE2F55NH000014", 2022, "EX", 500L, List.of("all-wheel-drive", "heated-seats"), 5, 2000000L,
            NOW.minusSeconds(8 * 86400)),
        listing("ex-one-more-miles", "2HGFE2F55NH000015", 2022, "EX", 2000L, List.of("all-wheel-drive", "heated-seats"), 5, 2000000L, NOW)));
    assertThat(results.candidates()).extracting(CandidateVehicle::listingId)
        .containsExactly("lx-both", "ex-one", "ex-one-more-miles", "ex-one-stale", "lx-one");
    // Two EX references with different case read as one reference trim.
    assertThat(results.candidates().get(1).matched()).extracting(CandidateVehicle.Reason::text)
        .contains("Matches your reference trim (EX).");
    assertThat(results.candidates().get(4).missed()).extracting(CandidateVehicle.Reason::text)
        .contains("Trim LX differs from your reference trim (EX).");
    assertThat(results.candidates().get(3).snapshot().staleness()).isEqualTo(InventoryListing.Staleness.STALE);
  }

  @Test void onlyRelevantReferenceDetailsAreUsed() {
    var results = CandidateMatcher.match(brief(), List.of(reference("2HGFE2F55NH000008", "EX", false)), List.of(
        listing("a", "2HGFE2F55NH000011", 2022, "EX", 1000L, ALL, 5, 2000000L, NOW)));
    assertThat(results.candidates().getFirst().matched()).noneMatch(r -> r.kind() == CandidateVehicle.Kind.REFERENCE);
    assertThat(results.candidates().getFirst().unconfirmed()).isEmpty();
  }

  @Test void aListingWithNoStatedFactsIsKeptLastWithEveryGapNamed() {
    var bare = listing("bare", new InventoryListing.Dealer("d", "D", null, null), null,
        new InventoryListing.Facts(null, null, null, null, null, null, null), null, null);
    var results = CandidateMatcher.match(brief(), List.of(), List.of(bare,
        listing("good", "2HGFE2F55NH000011", 2022, "EX", 1000L, ALL, 5, 2000000L, NOW)));
    assertThat(results.candidates()).extracting(CandidateVehicle::listingId).containsExactly("good", "bare");
    var last = results.candidates().get(1);
    assertThat(last.title()).isEqualTo("Vehicle with no stated details");
    assertThat(last.unconfirmed()).hasSize(7);
    assertThat(last.snapshot().staleness()).isEqualTo(InventoryListing.Staleness.UNKNOWN);
  }

  @Test void matchingIsDeterministicWhateverOrderListingsArriveIn() {
    var listings = new ArrayList<InventoryListing>();
    var random = new Random(55);
    for (int i = 0; i < 40; i++) listings.add(CandidateRankingIntegrityTest.randomListing(random, i));
    var first = CandidateMatcher.match(brief(), List.of(reference("2HGFE2F55NH000008", "EX", true)), listings);
    for (int round = 0; round < 20; round++) {
      Collections.shuffle(listings, new Random(round));
      assertThat(CandidateMatcher.match(brief(), List.of(reference("2HGFE2F55NH000008", "EX", true)), listings))
          .isEqualTo(first);
    }
    assertThat(CandidateMatcher.VERSION).isEqualTo("candidate-matching.v1");
  }

  @Test void noCandidateFieldCarriesAScore() {
    for (Class<?> type : List.of(CandidateVehicle.class, CandidateVehicle.Reason.class, CandidateVehicle.Snapshot.class,
        CandidateVehicle.Excluded.class, CandidateMatchingService.CandidateResults.class)) {
      assertThat(Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName))
          .as(type.getSimpleName()).noneMatch(name -> name.matches("(?i).*(score|rank|weight|points|rating).*"));
    }
  }
}

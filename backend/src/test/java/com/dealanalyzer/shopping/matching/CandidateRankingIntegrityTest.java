package com.dealanalyzer.shopping.matching;

import static org.assertj.core.api.Assertions.*;

import com.dealanalyzer.journey.ShoppingBrief;
import com.dealanalyzer.shopping.inventory.InventoryListing;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Ranking integrity (acceptance scenario 17, SHOP-05): changing a candidate's dealer, or adding any
 * commercial field, changes neither ordering nor wording.
 */
class CandidateRankingIntegrityTest {
  private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  private static final List<String> FEATURES = List.of("all-wheel-drive", "heated-seats", "apple-carplay", "sunroof-or-moonroof");
  private static final List<String> TRIMS = List.of("EX", "LX", "Sport");
  private static final String[] COMMERCIAL = {"sponsored", "promoted", "featuredPlacement", "leadFeeUsd", "referralFeeMinor",
      "dealerIncentiveMinor", "holdbackMinor", "commissionRate", "advertiserTier", "paidBoost", "paymentOfferMonthly",
      "acquisitionChannel", "partnerId", "dealerRating"};

  static InventoryListing randomListing(Random random, int index) {
    var features = new ArrayList<String>();
    for (String feature : FEATURES) if (random.nextBoolean()) features.add(feature);
    var facts = new InventoryListing.Facts(random.nextInt(8) == 0 ? null : 2018 + random.nextInt(8), "Honda",
        random.nextInt(10) == 0 ? "Accord" : "Civic", random.nextInt(8) == 0 ? null : TRIMS.get(random.nextInt(TRIMS.size())),
        random.nextInt(8) == 0 ? null : (long) random.nextInt(4) * 20000, features,
        random.nextInt(8) == 0 ? null : random.nextInt(4) * 20);
    Long price = random.nextInt(8) == 0 ? null : 2800000L + random.nextInt(3) * 200000L;
    var observed = random.nextInt(5) == 0 ? CandidateMatcherTest.NOW.minusSeconds(8 * 86400L)
        : random.nextInt(8) == 0 ? null : CandidateMatcherTest.NOW;
    String vin = random.nextInt(6) == 0 ? null : String.format("2HGFE2F55NH%06d", index);
    return CandidateMatcherTest.listing("listing-" + index, randomDealer(random), vin, facts, price, observed);
  }

  static InventoryListing.Dealer randomDealer(Random random) {
    return new InventoryListing.Dealer("dealer-" + random.nextInt(1000), "Dealer " + Long.toHexString(random.nextLong()),
        random.nextBoolean() ? null : "Town " + random.nextInt(50), random.nextBoolean() ? "US-NC" : "US-VA");
  }

  private static InventoryListing withDealer(InventoryListing row, InventoryListing.Dealer dealer) {
    return new InventoryListing(row.listingId(), dealer, row.vin(), row.facts(), row.advertisedPriceMinor(), row.currency(),
        row.source(), row.licenceMetadata(), row.retrievedAt(), row.observedAt(), row.staleness(), row.synthetic());
  }

  private static ShoppingBrief brief(Random random) {
    var base = CandidateMatcherTest.brief();
    return random.nextBoolean() ? base : new ShoppingBrief(base.journeyId(), base.version(), base.referenceListVersion(),
        base.yearFrom(), base.yearTo(), base.make(), base.model(), List.of(), base.maxMileage(), base.priceCeilingMinor(),
        base.currency(), base.postalCode(), base.jurisdiction(), base.radiusMiles(), base.mustHaveFeatures(),
        base.niceToHaveFeatures(), base.createdAt());
  }

  @Test void changingDealerIdentityOrNameNeverChangesOrderingOrWording() {
    for (int seed = 0; seed < 300; seed++) {
      var random = new Random(seed);
      var brief = brief(random);
      var references = List.of(CandidateMatcherTest.reference("2HGFE2F55NH000008", TRIMS.get(random.nextInt(3)), true));
      var listings = new ArrayList<InventoryListing>();
      for (int i = 0; i < 2 + random.nextInt(12); i++) listings.add(randomListing(random, i));
      var baseline = CandidateMatcher.match(brief, references, listings);

      // Every dealer replaced, one dealer for all, and only one listing's dealer changed.
      var shared = randomDealer(random);
      var renamed = listings.stream().map(row -> withDealer(row, randomDealer(random))).toList();
      var same = listings.stream().map(row -> withDealer(row, shared)).toList();
      var one = new ArrayList<>(listings);
      int pick = random.nextInt(one.size());
      var original = one.get(pick).dealer();
      one.set(pick, withDealer(one.get(pick), new InventoryListing.Dealer(original.id() + "-other",
          "Renamed " + original.publicName(), original.locality(), original.jurisdiction())));
      for (var variant : List.of(renamed, same, one))
        assertThat(CandidateMatcher.match(brief, references, variant)).as("seed " + seed).isEqualTo(baseline);
    }
  }

  @Test void addingAnyCommercialFieldToAListingNeverChangesOrderingOrWording() throws Exception {
    for (int seed = 0; seed < 100; seed++) {
      var random = new Random(1000 + seed);
      var brief = brief(random);
      var listings = new ArrayList<InventoryListing>();
      for (int i = 0; i < 2 + random.nextInt(10); i++) listings.add(randomListing(random, i));
      var baseline = CandidateMatcher.match(brief, List.of(), listings);
      var commercial = new ArrayList<InventoryListing>();
      for (var row : listings) {
        ObjectNode payload = JSON.valueToTree(row);
        for (String field : COMMERCIAL) if (random.nextBoolean()) payload.put(field, random.nextInt(10_000));
        ((ObjectNode) payload.get("dealer")).put("paidPlacement", random.nextBoolean());
        commercial.add(JSON.treeToValue(payload, InventoryListing.class));
      }
      assertThat(CandidateMatcher.match(brief, List.of(), commercial)).as("seed " + seed).isEqualTo(baseline);
    }
  }

  /**
   * Matching sees a listing only through {@link ListingEvidence}. Any new field on the listing must be
   * classified here: it is either copied into the evidence or kept out of matching on purpose.
   */
  @Test void everyListingFieldIsClassifiedAndOnlyEvidenceReachesMatching() {
    var listing = Arrays.stream(InventoryListing.class.getRecordComponents()).map(RecordComponent::getName).toList();
    var evidence = Arrays.stream(ListingEvidence.class.getRecordComponents()).map(RecordComponent::getName).toList();
    var keptOut = List.of("dealer", "licenceMetadata");
    assertThat(listing).containsExactlyInAnyOrderElementsOf(
        java.util.stream.Stream.concat(evidence.stream(), keptOut.stream()).toList());
    assertThat(evidence).doesNotContain("dealer").noneMatch(name -> name.matches("(?i).*(dealer|seller|fee|sponsor|promot).*"));
    assertThat(Arrays.stream(InventoryListing.Facts.class.getRecordComponents()).map(RecordComponent::getName))
        .containsExactly("year", "make", "model", "trim", "mileage", "features", "distanceMiles");
  }
}

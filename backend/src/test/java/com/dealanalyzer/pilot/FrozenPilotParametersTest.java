package com.dealanalyzer.pilot;

import static org.assertj.core.api.Assertions.assertThat;

import com.dealanalyzer.economics.CashDealEconomics.*;
import com.dealanalyzer.economics.CashDealEconomicsCalculator;
import com.dealanalyzer.market.MarketAssessment;
import com.dealanalyzer.market.MarketAssessmentPolicy;
import com.dealanalyzer.market.MarketDataProvider.ComparableObservation;
import com.dealanalyzer.market.MarketDataProvider.MarketVehicle;
import com.dealanalyzer.market.MarketDataProvider.ProviderResult;
import com.dealanalyzer.market.MarketDataProvider.ProviderSnapshot;
import com.dealanalyzer.negotiation.GroundedLanguage;
import com.dealanalyzer.plan.PriceBoundaryPolicy;
import com.dealanalyzer.report.CoreReviewPolicy;
import com.dealanalyzer.review.RevisedOfferPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Currency;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Drift guard for the frozen pilot parameters. Configuration values are compared with the shipped
 * application defaults; rules that only exist as behavior are exercised at their policy seams.
 */
class FrozenPilotParametersTest {
  private static final Pattern DEFAULT = Pattern.compile("^([^=]+)=\\$\\{[A-Z_]+:(.*)}$");
  private static final Instant RETRIEVED = Instant.parse("2026-08-27T12:00:00Z");
  private static final MarketVehicle VEHICLE =
      new MarketVehicle(null, 2021, "Honda", "Accord", "EX-L", 40_000L, "Raleigh, NC");
  private static final ProviderSnapshot SNAPSHOT = new ProviderSnapshot(
      "RECORDED", "provider.v1", "market-provider.v1", "licensed", "fixture://frozen", RETRIEVED);

  private final ObjectMapper json = new ObjectMapper();
  private final JsonNode frozen = read();

  @Test void uploadLimitsMatchTheShippedApplicationDefaults() throws IOException {
    var limits = frozen.path("uploadLimits");
    assertThat(applicationDefault("document.limits.max-file-bytes"))
        .isEqualTo(limits.path("maxFileBytes").asText());
    assertThat(applicationDefault("document.limits.max-pages"))
        .isEqualTo(limits.path("maxPages").asText());
    assertThat(applicationDefault("document.limits.max-pixels"))
        .isEqualTo(limits.path("maxPixels").asText());
    assertThat(applicationDefault("document.limits.min-width"))
        .isEqualTo(limits.path("minWidth").asText());
    assertThat(applicationDefault("document.limits.min-height"))
        .isEqualTo(limits.path("minHeight").asText());
    assertThat(applicationDefault("document.limits.max-expansion-ratio"))
        .isEqualTo(limits.path("maxExpansionRatio").asText());
    assertThat(applicationDefault("document.normalization.dpi"))
        .isEqualTo(limits.path("normalizationDpi").asText());
    assertThat(applicationDefault("document.normalization.max-page-pixels"))
        .isEqualTo(limits.path("normalizationMaxPagePixels").asText());
  }

  @Test void theMaterialConfidenceThresholdMatchesTheShippedApplicationDefault() throws IOException {
    assertThat(applicationDefault("extraction.material-confidence-threshold"))
        .isEqualTo(frozen.path("confidenceThresholds").path("materialExtractionConfidence").asText());
  }

  @Test void theFrozenMaterialityToleranceIsThePublishedRevisedOfferTolerance() {
    assertThat(RevisedOfferPolicy.TOLERANCE)
        .isEqualTo(frozen.path("materialityTolerances").path("revisedOfferTolerance").asText());
  }

  @Test void changesBelowTheFrozenCurrencyThresholdStayInformational() throws Exception {
    long threshold = frozen.path("materialityTolerances").path("usdMinorThreshold").asLong();
    assertThat(severityOfSellingPriceChange(2_000_000L, 2_000_000L + threshold - 1))
        .isEqualTo("INFORMATIONAL");
    assertThat(severityOfSellingPriceChange(2_000_000L, 2_000_000L + threshold))
        .isEqualTo("MATERIAL");
  }

  @Test void aprChangesUseTheFrozenPercentagePointThreshold() throws Exception {
    var threshold = frozen.at("/materialityTolerances/percentagePointThreshold").decimalValue();
    var before = json.readTree("""
        {"candidates":[{"field":"apr", "value":5.0, "unit":"PERCENT", "buyerVerified":true}]}
        """);
    for (var delta : List.of(threshold.subtract(new BigDecimal("0.001")), threshold)) {
      var after = before.deepCopy();
      ((com.fasterxml.jackson.databind.node.ObjectNode) after.at("/candidates/0"))
          .put("value", new BigDecimal("5.0").add(delta));
      var differences = new RevisedOfferPolicy().compare("BASELINE", before, after);
      assertThat(differences).hasSize(1);
      assertThat(differences.getFirst().severity())
          .isEqualTo(delta.compareTo(threshold) < 0 ? "INFORMATIONAL" : "MATERIAL");
    }
  }

  @Test void coreReviewRejectsEvenOneMinorUnitOfTermDrift() throws Exception {
    assertThat(frozen.at("/materialityTolerances/coreReviewTolerance").asText()).isEqualTo("zero");
    var cash = coreCash("10000", "11000");
    var plan = new PriceBoundaryPolicy.Plan("v1", "USD",
        new PriceBoundaryPolicy.Boundary(900_000, 1_000_000, 1_500_000, 1_500_000),
        new PriceBoundaryPolicy.Boundary(990_000, 1_100_000, 1_650_000, 1_650_000),
        1_500_000L, 1_650_000L, null, List.of(), "ESTIMATED", "fixture");
    var baseline = terms(1_000_000);
    var review = new CoreReviewPolicy();
    assertThat(review.review(true, cash, baseline, baseline, cash, plan, true))
        .isEqualTo("MATCHES_APPROVED_BASELINE");
    assertThat(review.review(true, cash, terms(1_000_001), baseline, cash, plan, true))
        .isEqualTo("REVIEW_REQUIRED");
    assertThat(review.review(true, coreCash("10000.01", "11000.01"), baseline, baseline, cash, plan, true))
        .isEqualTo("REVIEW_REQUIRED");
  }

  private static Result coreCash(String price, String quotedTotal) {
    var usd = Currency.getInstance("USD");
    var policy = new Policy("tax.v1", "fixture", usd, new BigDecimal("0.10"), Set.of(LineType.SELLING_PRICE),
        false, 2, RoundingMode.HALF_UP, BigDecimal.ZERO);
    var offer = new Offer("offer", 1, usd,
        List.of(new LineItem("price", "Selling price", new Money(new BigDecimal(price), usd),
            LineType.SELLING_PRICE, TaxTreatment.TAXABLE, Eligibility.NOT_APPLICABLE)),
        new Money(new BigDecimal(quotedTotal), usd), "quote");
    return new CashDealEconomicsCalculator().calculate(offer, policy);
  }

  @Test void observationsOlderThanTheFrozenFreshnessRuleAreExcludedAsStale() {
    Duration maxAge = Duration.ofDays(frozen.path("marketRules").path("maxObservationAgeDays").asLong());
    var assessment = assess(
        observation("fresh-one", 2_400_000L, RETRIEVED.minus(maxAge)),
        observation("fresh-two", 2_450_000L, RETRIEVED.minus(maxAge)),
        observation("fresh-three", 2_500_000L, RETRIEVED.minus(maxAge)),
        observation("stale", 2_460_000L, RETRIEVED.minus(maxAge).minusMillis(1)));

    assertThat(assessment.status()).isEqualTo(MarketAssessment.Status.AVAILABLE);
    assertThat(assessment.sampleSize()).isEqualTo(3);
    assertThat(assessment.decisions()).anySatisfy(decision -> {
      assertThat(decision.observation().providerObservationId()).isEqualTo("stale");
      assertThat(decision.reasonCode()).isEqualTo("STALE");
    });
  }

  @Test void pricesOutsideTheFrozenPlausibilityBoundsAreExcluded() {
    var rules = frozen.path("marketRules");
    long low = rules.path("minObservationPriceMinor").asLong();
    long high = rules.path("maxObservationPriceMinor").asLong();
    var assessment = assess(
        observation("at-low-bound", low, RETRIEVED),
        observation("at-high-bound", high, RETRIEVED),
        observation("mid", (low + high) / 2, RETRIEVED),
        observation("below", low - 1, RETRIEVED),
        observation("above", high + 1, RETRIEVED));

    assertThat(assessment.sampleSize()).isEqualTo(3);
    assertThat(assessment.decisions())
        .filteredOn(decision -> "IMPLAUSIBLE_PRICE".equals(decision.reasonCode()))
        .extracting(decision -> decision.observation().providerObservationId())
        .containsExactlyInAnyOrder("below", "above");
  }

  @Test void fewerComparablesThanTheFrozenMinimumAreInsufficient() {
    int minimum = frozen.path("marketRules").path("minIncludedComparables").asInt();
    var usable = new ComparableObservation[minimum];
    for (int index = 0; index < minimum; index++) {
      usable[index] = observation("usable-" + index, 2_400_000L + index * 10_000L, RETRIEVED);
    }
    assertThat(assess(usable).status()).isEqualTo(MarketAssessment.Status.AVAILABLE);

    var sparse = java.util.Arrays.copyOf(usable, minimum - 1);
    assertThat(assess(sparse).status()).isEqualTo(MarketAssessment.Status.INSUFFICIENT_DATA);
  }

  @Test void theFrozenPolicyVersionsMatchTheVersionsTheEnginesPublish() {
    var versions = frozen.path("reportSchema").path("policyVersions");
    assertThat(PriceBoundaryPolicy.VERSION).isEqualTo(versions.path("priceBoundaries").asText());
    assertThat(RevisedOfferPolicy.VERSION).isEqualTo(versions.path("revisedOffer").asText());
    assertThat(CoreReviewPolicy.VERSION).isEqualTo(versions.path("coreReview").asText());
    assertThat(MarketAssessmentPolicy.VERSION).isEqualTo(versions.path("marketSelection").asText());
    assertThat(com.dealanalyzer.finance.FinancingCalculator.VERSION)
        .isEqualTo(versions.path("dealerFinancing").asText());
    assertThat(com.dealanalyzer.finance.FinancingComparison.VERSION)
        .isEqualTo(versions.path("outsideFinancingComparison").asText());
    assertThat(GroundedLanguage.VERSION).isEqualTo(versions.path("groundedLanguage").asText());
  }

  @Test void frozenReportFixturesDescribeTheirDeclaredContracts() throws Exception {
    var schema = frozen.path("reportSchema");
    try (var input = getClass().getClassLoader().getResourceAsStream(schema.path("coreReportFixture").asText())) {
      assertThat(input).isNotNull();
      assertThat(json.readTree(input).path("schemaVersion").asText())
          .isEqualTo(schema.path("reportSchemaVersion").asText());
    }
    assertThat(schema.path("syntheticGoldenFixtures").size()).isPositive();
    for (JsonNode fixture : schema.path("syntheticGoldenFixtures")) {
      try (var input = getClass().getClassLoader().getResourceAsStream(fixture.asText())) {
        assertThat(input).as(fixture.asText()).isNotNull();
        assertThat(json.readTree(input).at("/expected/reportSchemaVersion").asText())
            .isEqualTo(schema.path("syntheticReportSchemaVersion").asText());
      }
    }
  }

  private String severityOfSellingPriceChange(long before, long after) throws IOException {
    var differences = new RevisedOfferPolicy().compare("BASELINE", terms(before), terms(after));
    assertThat(differences).hasSize(1);
    return differences.get(0).severity();
  }

  private JsonNode terms(long sellingPriceMinor) throws IOException {
    return json.readTree("""
        {"lineItems":[{"originalLabel":"Selling price","amountMinor":%d,"currency":"USD",
          "category":"SELLING_PRICE","taxClaim":"TAXABLE","optionalityClaim":"REQUIRED",
          "buyerVerified":true}]}
        """.formatted(sellingPriceMinor));
  }

  private MarketAssessment assess(ComparableObservation... observations) {
    return new MarketAssessmentPolicy()
        .assess(VEHICLE, ProviderResult.available(SNAPSHOT, List.of(observations)));
  }

  private static ComparableObservation observation(String id, long priceMinor, Instant observedAt) {
    return new ComparableObservation(id, "https://example/" + id, 2021, "Honda", "Accord", "EX-L",
        40_000L, priceMinor, "USD", "Raleigh, NC", observedAt);
  }

  private static String applicationDefault(String key) throws IOException {
    Path properties = Path.of("src/main/resources/application.properties");
    for (String line : Files.readAllLines(properties, StandardCharsets.UTF_8)) {
      var matched = DEFAULT.matcher(line.strip());
      if (matched.matches() && matched.group(1).equals(key)) return matched.group(2);
    }
    throw new AssertionError("No ${ENV:default} entry for " + key + " in " + properties);
  }

  private JsonNode read() {
    try (InputStream source = getClass().getResourceAsStream("/pilot/pilot-parameters.v1.json")) {
      return json.readTree(source);
    } catch (IOException cause) {
      throw new IllegalStateException("Unreadable frozen pilot parameters", cause);
    }
  }
}

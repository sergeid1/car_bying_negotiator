package com.dealanalyzer.pilot;

import static org.assertj.core.api.Assertions.assertThat;

import com.dealanalyzer.ai.ProhibitedClaims;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Buyer-facing copy may not make unsupported law, advice, safety, fraud, guarantee, or savings
 * claims, and may not say the platform negotiates, contacts dealers, or acts on the Buyer's behalf
 * (DEVELOPMENT_SPEC.md sections 1.2 and 1.3). Each risky phrase must be recorded as reviewed, so the
 * claim surface cannot grow silently; the standing positioning and commercial-relationship
 * disclosures must remain present.
 */
class PilotCopyClaimsTest {
  private static final Path REPOSITORY = Path.of("..").toAbsolutePath().normalize();

  /** One pattern per prohibited claim category in the tickets. */
  private static final Map<String, Pattern> PROHIBITED = ProhibitedClaims.PATTERNS;

  private final ObjectMapper json = new ObjectMapper();

  @Test void quoteClaimsAreProhibitedIncludingIdentifiersAndComments() {
    for (String copy : List.of("best rate", "bestRate", "cheapest", "guaranteed", "// Guaranteed rate"))
      assertThat(PROHIBITED.get("guarantee").matcher(copy).find()).as(copy).isTrue();
  }

  @Test void shippedCopyMakesNoUnreviewedClaim() throws IOException {
    var reviewed = reviewed();
    List<String> unreviewed = new ArrayList<>();
    for (Path file : buyerFacingCopy()) {
      String relative = REPOSITORY.relativize(file).toString();
      String copy = normalize(Files.readString(file, StandardCharsets.UTF_8));
      for (var claim : PROHIBITED.entrySet()) {
        var matches = claim.getValue().matcher(copy);
        while (matches.find()) {
          if (isReviewed(reviewed, relative, claim.getKey(), copy, matches.start(), matches.end())) continue;
          unreviewed.add(relative + " [" + claim.getKey() + "] near: "
              + copy.substring(Math.max(0, matches.start() - 60), Math.min(copy.length(), matches.end() + 60)));
        }
      }
    }
    assertThat(unreviewed)
        .as("Record each phrase in backend/src/test/resources/pilot/reviewed-claim-phrases.json "
            + "with a justification, or remove the claim from Buyer-facing copy")
        .isEmpty();
  }

  @Test void everyReviewedPhraseStillExistsInTheCopyItDescribes() throws IOException {
    List<String> stale = new ArrayList<>();
    for (JsonNode entry : reviewed()) {
      Path file = REPOSITORY.resolve(entry.path("file").asText());
      String contains = entry.path("contains").asText();
      assertThat(contains).isNotBlank();
      assertThat(buyerFacingCopy()).as("reviewed copy must be scanned").contains(file);
      if (!Files.exists(file) || !normalize(Files.readString(file, StandardCharsets.UTF_8)).contains(normalize(contains))) {
        stale.add(entry.path("file").asText() + " no longer contains: " + contains);
      }
      assertThat(entry.path("justification").asText()).as("justification for " + contains).isNotBlank();
    }
    assertThat(stale).as("Remove reviewed entries whose copy is gone").isEmpty();
  }

  @Test void theStandingCommercialRelationshipDisclosureIsPresent() throws IOException {
    String app = normalize(Files.readString(REPOSITORY.resolve("frontend/src/App.tsx"), StandardCharsets.UTF_8));
    assertThat(app).contains("otdog is on your side.");
    assertThat(app).contains("No dealer pays us, and no company can pay to change what you see.");
    assertThat(app).contains("It isn't legal, tax or mechanical advice, and it can't promise savings.");
    assertThat(app).contains("You send every email and make every decision.");
    assertThat(app).contains("How otdog makes money");
  }

  @Test void thePartnerKitKeepsTheCredoAndTheNoDealerMoneyTerms() throws IOException {
    String kit = normalize(Files.readString(REPOSITORY.resolve("frontend/src/ReferralPartnerKit.tsx"), StandardCharsets.UTF_8));
    assertThat(kit).contains("does not contact dealers for the Buyer");
    assertThat(kit).contains("We don't work with dealers. We help people.");
    assertThat(kit).contains("The Buyer sends every email from their own email and makes every decision");
    assertThat(kit).contains("No dealer pays otdog for anything, and no company pays for placement.");
  }

  @Test void positioningClaimsAreProhibited() {
    for (String claim : List.of("Our AI negotiator negotiates for you.", "We contact dealers on your behalf.",
        "otdog emails the dealer for you.", "Let our agent handle it.", "An autonomous buying assistant.",
        "The platform writes to the dealer in its own name.", "It negotiates with dealers for the Buyer.",
        "otdog represents you at the dealership.", "This contract is safe to sign.", "Guaranteed savings.")) {
      assertThat(PROHIBITED.values()).as(claim).anyMatch(pattern -> pattern.matcher(claim).find());
    }
    for (String allowed : List.of("otdog helps you draft your emails to dealers.",
        "You send every email from your own email.", "Enter the vehicle you are negotiating for.")) {
      assertThat(PROHIBITED.values()).as(allowed).noneMatch(pattern -> pattern.matcher(allowed).find());
    }
  }

  @Test void theCopyUnderReviewIsActuallyBeingScanned() throws IOException {
    assertThat(buyerFacingCopy())
        .as("the scan must reach shipped frontend and generated-language copy")
        .anyMatch(file -> file.endsWith("App.tsx"))
        .anyMatch(file -> file.endsWith("GroundedLanguage.java"))
        .anyMatch(file -> file.endsWith("CoreReportService.java"))
        .anyMatch(file -> file.endsWith("CashDealAnalyzer.java"))
        .anyMatch(file -> file.endsWith("toto/guide/doc-fee.md"));
  }

  /**
   * Toto quotes guide passages, and its answer check rejects any prohibited claim with no reviewed exception, so a
   * guide article may not contain one at all, not even a phrase recorded as reviewed elsewhere (ticket 118).
   */
  @Test void theTotoGuideContainsNoProhibitedClaimAtAll() throws IOException {
    List<Path> guide = totoGuide();
    assertThat(guide).as("the Toto guide must be scanned").hasSizeGreaterThanOrEqualTo(30);
    List<String> claims = new ArrayList<>();
    for (Path file : guide) {
      String copy = normalize(Files.readString(file, StandardCharsets.UTF_8));
      for (var claim : PROHIBITED.entrySet()) {
        var matches = claim.getValue().matcher(copy);
        while (matches.find()) claims.add(REPOSITORY.relativize(file) + " [" + claim.getKey() + "] near: "
            + copy.substring(Math.max(0, matches.start() - 60), Math.min(copy.length(), matches.end() + 60)));
      }
    }
    assertThat(claims).as("Toto would reject these passages; reword the article").isEmpty();
  }

  private static boolean isReviewed(JsonNode reviewed, String file, String pattern, String copy,
      int start, int end) {
    for (JsonNode entry : reviewed) {
      if (entry.path("file").asText().equals(file)
          && entry.path("pattern").asText().equals(pattern)) {
        String phrase = normalize(entry.path("contains").asText());
        if (phrase.isBlank()) continue;
        for (int at = copy.indexOf(phrase); at >= 0; at = copy.indexOf(phrase, at + 1)) {
          if (at <= start && at + phrase.length() >= end) return true;
        }
      }
    }
    return false;
  }

  private static String normalize(String copy) {
    return copy.replaceAll("\\s+", " ").strip();
  }

  @Test void wrappedClaimsAreFoundAndReviewedExceptionsCoverOnlyTheirOwnOccurrence() throws Exception {
    String copy = normalize("does not guarantee\n savings; we guarantee a discount");
    var reviewed = json.readTree("""
        [{"file":"example", "pattern":"guarantee", "contains":"does not guarantee savings"}]
        """);
    var matches = PROHIBITED.get("guarantee").matcher(copy);
    assertThat(matches.find()).isTrue();
    assertThat(isReviewed(reviewed, "example", "guarantee", copy, matches.start(), matches.end())).isTrue();
    assertThat(matches.find()).isTrue();
    assertThat(isReviewed(reviewed, "example", "guarantee", copy, matches.start(), matches.end())).isFalse();
    assertThat(PROHIBITED.get("safe to sign").matcher(normalize("This is safe\n to sign")).find()).isTrue();
  }

  private JsonNode reviewed() {
    try (InputStream source = getClass().getResourceAsStream("/pilot/reviewed-claim-phrases.json")) {
      return json.readTree(source).path("reviewed");
    } catch (IOException cause) {
      throw new UncheckedIOException(cause);
    }
  }

  /** Shipped Buyer-facing surfaces: the web client, the language the product generates, and Toto's guide. */
  private static List<Path> buyerFacingCopy() throws IOException {
    Path frontend = REPOSITORY.resolve("frontend/src");
    if (!Files.isDirectory(frontend)) throw new AssertionError("Missing frontend copy at " + frontend);
    try (Stream<Path> files = Files.walk(frontend)) {
      var scanned = new ArrayList<>(files
          .filter(Files::isRegularFile)
          .filter(file -> file.toString().endsWith(".tsx") || file.toString().endsWith(".ts"))
          .filter(file -> !file.getFileName().toString().matches(".*\\.(test|spec)\\.tsx?"))
          .sorted().toList());
      for (String generated : List.of(
          "backend/src/main/java/com/dealanalyzer/negotiation/GroundedLanguage.java",
          "backend/src/main/java/com/dealanalyzer/negotiation/GroundedReply.java",
          "backend/src/main/java/com/dealanalyzer/report/CoreReviewPolicy.java",
          "backend/src/main/java/com/dealanalyzer/plan/PriceBoundaryPolicy.java",
          "backend/src/main/java/com/dealanalyzer/review/RevisedOfferPolicy.java",
          "backend/src/main/java/com/dealanalyzer/buyingcase/persistence/CoreReportService.java",
          "backend/src/main/java/com/dealanalyzer/report/CashDealAnalyzer.java",
          "backend/src/main/java/com/dealanalyzer/report/AmountEvidence.java",
          "backend/src/main/java/com/dealanalyzer/report/StoredReportRenderer.java",
          "backend/src/main/java/com/dealanalyzer/listing/capture/ListingPriceCheck.java",
          "backend/src/main/java/com/dealanalyzer/economics/fees/GovernmentFeeCheck.java",
          "backend/src/main/resources/fees/government-fees.v1.json",
          "backend/src/main/java/com/dealanalyzer/economics/fees/DocFeeCheck.java",
          "backend/src/main/resources/fees/doc-fee-rules.v1.json",
          "backend/src/main/java/com/dealanalyzer/economics/fees/DealerAddOnCheck.java",
          "backend/src/main/resources/fees/dealer-add-ons.v1.json")) {
        Path file = REPOSITORY.resolve(generated);
        if (!Files.exists(file)) throw new AssertionError("Missing generated copy at " + file);
        scanned.add(file);
      }
      scanned.addAll(totoGuide());
      return scanned;
    }
  }

  /** Toto's reviewed guide articles, which reach Buyers as quoted answer passages. */
  private static List<Path> totoGuide() throws IOException {
    Path guide = REPOSITORY.resolve("backend/src/main/resources/toto/guide");
    if (!Files.isDirectory(guide)) throw new AssertionError("Missing Toto guide at " + guide);
    try (Stream<Path> files = Files.list(guide)) {
      return files.filter(file -> file.toString().endsWith(".md")).sorted().toList();
    }
  }

}

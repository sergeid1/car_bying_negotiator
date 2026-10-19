package com.dealanalyzer.economics.fees;

import static org.assertj.core.api.Assertions.assertThat;

import com.dealanalyzer.ai.ProhibitedClaims;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Ticket 142: labelled synthetic paperwork lines against the dealer add-on catalogue. */
class DealerAddOnCheckTest {
  private final ObjectMapper json = new ObjectMapper();
  private final DealerAddOnCatalogue catalogue = new DealerAddOnCatalogue(json);
  private final DealerAddOnCheck check = new DealerAddOnCheck(catalogue, new DocFeeCheck(null, null, json),
      GovernmentFeeCheckTest.DATA, null);

  private ObjectNode terms(Object... lines) {
    var terms = json.createObjectNode();
    terms.putArray("candidates");
    ArrayNode items = terms.putArray("lineItems");
    for (int i = 0; i < lines.length; i += 3)
      items.addObject().put("originalLabel", (String) lines[i]).put("category", (String) lines[i + 1])
          .put("amountMinor", ((Number) lines[i + 2]).longValue()).put("currency", "USD").put("buyerVerified", true);
    return terms;
  }

  private DealerAddOnCheck.Line only(String label, String category) {
    var result = check.evaluate(terms(label, category, 29900), false, null);
    assertThat(result).as(label).isNotNull();
    assertThat(result.lines()).as(label).hasSize(1);
    return result.lines().getFirst();
  }

  @Test void everyFamilyIsRecognisedUnderSeveralSpellings() {
    Map<String, List<String>> spellings = Map.ofEntries(
        Map.entry("paint-protection", List.of("Paint Prot.", "PAINT/FABRIC PKG", "Paint sealant", "Paitn Protection")),
        Map.entry("fabric-protection", List.of("PAINT/FABRIC PKG", "Fabric Guard", "INT. PROT.")),
        Map.entry("undercoating", List.of("Undercoating", "Rust-Proofing", "Electronic rust module")),
        Map.entry("protection-package", List.of("Appearance Pkg", "Environmental Protection Pkg", "ENV PKG")),
        Map.entry("ceramic-coating", List.of("Ceramic Coating", "Nano coat")),
        Map.entry("vin-etch", List.of("Etch", "VIN ETCH", "Window Etching", "Vin etchign")),
        Map.entry("theft-protection", List.of("Theft Deterrent Sys", "Anti-Theft", "Theft Registration")),
        Map.entry("tracking-device", List.of("GPS Tracker", "Stolen vehicle recovery", "LoJack")),
        Map.entry("nitrogen-tires", List.of("N2 Tires", "Nitrogen", "Nitro-Fill")),
        Map.entry("wheel-locks", List.of("Wheel Locks", "Locking lugs")),
        Map.entry("mud-flaps", List.of("Mud Flaps", "Splash Guards")),
        Map.entry("door-edge-guards", List.of("Door Edge Guards", "Door handle cups")),
        Map.entry("pinstripe", List.of("Pinstripe", "PIN STRIPE")),
        Map.entry("window-tint", List.of("Window Tint", "TINT")),
        Map.entry("floor-mats", List.of("Floor Mats", "All-Weather Mats")),
        Map.entry("cargo-tray", List.of("Cargo Tray", "Trunk liner")),
        Map.entry("dealer-accessories", List.of("Accessories", "Dealer installed options")),
        Map.entry("market-adjustment", List.of("Mkt Adj", "ADM", "Market Value Adjustment", "Addl. Dealer Markup")),
        Map.entry("dealer-addendum", List.of("Dealer Addendum", "Supplemental sticker")),
        Map.entry("premium-package", List.of("Premium Package", "Dealer Pkg")),
        Map.entry("dealer-prep", List.of("Dealer Prep", "PDI", "Pre-Delivery Inspection")),
        Map.entry("reconditioning", List.of("Reconditioning", "Recon fee")),
        Map.entry("destination-freight", List.of("Destination", "Freight", "Dest. Chg")),
        Map.entry("dealer-handling", List.of("Dealer Handling", "Handling fee")),
        Map.entry("service-contract", List.of("Service Contract", "Ext. Warranty", "VSC")),
        Map.entry("gap", List.of("GAP", "Gap Waiver", "Debt cancellation")),
        Map.entry("tire-wheel", List.of("Tire & Wheel", "T&W", "Road hazard")),
        Map.entry("key-replacement", List.of("Key Replacement", "Key fob plan")),
        Map.entry("dent-ding", List.of("Dent & Ding", "PDR")),
        Map.entry("windshield", List.of("Windshield Protection", "Windscreen")),
        Map.entry("maintenance-plan", List.of("Maintenance Plan", "Prepaid Maint.", "PPM")),
        Map.entry("credit-insurance", List.of("Credit Life", "Credit A&H", "Credit disability")));
    assertThat(spellings.keySet()).containsExactlyInAnyOrderElementsOf(
        catalogue.data().entries().stream().map(DealerAddOnCatalogue.Entry::id).toList());
    for (var family : spellings.entrySet())
      for (String label : family.getValue()) {
        var line = only(label, "ADD_ON");
        assertThat(line.status()).as(label).isEqualTo("RECOGNISED");
        assertThat(line.chargeIds()).as(label).contains(family.getKey());
        assertThat(line.name()).as(label).isNotBlank();
        assertThat(line.what()).as(label).isNotBlank();
        assertThat(line.question()).as(label).isNotBlank();
      }
  }

  @Test void everyCatalogueExampleIsRecognisedAsItsEntry() {
    for (var entry : catalogue.data().entries())
      for (String example : entry.examples()) {
        var match = catalogue.match(example);
        assertThat(match).as(example).isNotNull();
        assertThat(match.tier()).as(example).isEqualTo("RECOGNISED");
        assertThat(match.entries()).as(example).contains(entry);
      }
  }

  @Test void eachKindHasItsOwnWordingAndNoLegalClaim() {
    assertThat(only("Paint Prot.", "ADD_ON").kindWording()).isEqualTo("Usually optional and negotiable.");
    assertThat(only("Mkt Adj", "DEALER_FEE").kindWording()).startsWith("A price increase on the car, not a service.");
    assertThat(only("Dealer Prep", "DEALER_FEE").kindWording()).isEqualTo("May repeat a charge already on the paperwork.");
    assertThat(only("Service Contract", "ADD_ON").kindWording())
        .isEqualTo("Optional product; you can decline it or buy it elsewhere.");
    List<String> copy = new ArrayList<>(catalogue.data().kinds().values());
    for (var entry : catalogue.data().entries()) copy.addAll(List.of(entry.name(), entry.what(), entry.question()));
    for (String text : copy) {
      assertThat(ProhibitedClaims.contains(text)).as(text).isFalse();
      assertThat(text.toLowerCase()).as(text).doesNotContain("not allowed", "cannot charge", "not permitted", "required by law");
    }
  }

  @Test void anUncertainMatchIsShownAsAPossibleAddOnWithAQuestion() {
    var line = only("Shield Pkg Plus", "ADD_ON");
    assertThat(line.status()).isEqualTo("RECOGNISED");
    line = only("Exterior Shine", "ADD_ON");
    assertThat(line.status()).isEqualTo("POSSIBLE");
    assertThat(line.note()).isEqualTo("Possibly paint protection.");
    assertThat(line.question()).contains("“Exterior Shine”", "paint protection", "optional");
    assertThat(line.optional()).isFalse();
    var result = check.evaluate(terms("Exterior Shine", "ADD_ON", 49900, "Window Tint", "ADD_ON", 30000), false, null);
    assertThat(result.possibleTotalMinor()).isEqualTo(49900);
    assertThat(result.optionalTotalMinor()).isEqualTo(30000);
    assertThat(result.summary()).contains("1 possible add-on", "Possible add-ons total $499");
  }

  @Test void theSellingPriceTaxesAndStateFeesAreNotAddOns() {
    for (String label : List.of("Selling price", "Sales tax", "Title fee", "Retail price", "Registration", "State inspection fee"))
      for (String category : List.of("UNKNOWN", "SELLING_PRICE", "TAX", "GOVERNMENT_CHARGE"))
        assertThat(check.evaluate(terms(label, category, 150000), false, null)).as(label + " " + category).isNull();
    assertThat(check.evaluate(terms("Title fee", "DEALER_FEE", 7500), false, null)).isNull();
    assertThat(check.evaluate(terms("Doc fee", "DEALER_FEE", 89900), false, null)).isNull();
    assertThat(catalogue.match("Selling price")).isNull();
    assertThat(catalogue.match("Sales tax")).isNull();
    assertThat(catalogue.match("Title fee")).isNull();
    assertThat(catalogue.match("Paint")).isNotNull();
    assertThat(catalogue.match("Maint").entries()).extracting(DealerAddOnCatalogue.Entry::id).containsExactly("maintenance-plan");
  }

  @Test void aFinanceOfficeProductIsOptionalOnACashDealAndAddsInterestOnAFinancedDeal() {
    var cash = check.evaluate(terms("Ext. Warranty", "ADD_ON", 249500, "GAP", "ADD_ON", 89500), false, null);
    assertThat(cash.financed()).isFalse();
    assertThat(cash.lines().get(0).kindWording()).contains("Optional product", "decline");
    assertThat(cash.lines().get(0).note()).contains("cash purchase", "no interest");
    assertThat(cash.lines().get(1).note()).contains("covers a loan", "cash purchase", "Ask what it would cover");
    assertThat(cash.optionalTotalMinor()).isEqualTo(339000);
    var financed = check.evaluate(terms("Ext. Warranty", "ADD_ON", 249500, "GAP", "ADD_ON", 89500), true, null);
    for (var line : financed.lines()) assertThat(line.note()).contains("Financed with the car", "interest", "loan term");
    var unknown = check.evaluate(terms("Ext. Warranty", "ADD_ON", 249500), null, null);
    assertThat(unknown.financed()).isNull();
    assertThat(unknown.lines().getFirst().note()).startsWith("If it is financed");
    var withLoan = terms("GAP", "ADD_ON", 89500);
    ((ArrayNode) withLoan.path("candidates")).addObject().put("field", "AMOUNT_FINANCED").put("value", "3000000");
    assertThat(check.evaluate(withLoan, null, null).financed()).isTrue();
  }

  @Test void twoAddOnsOnOnePaperworkAreTotalledAndEveryLineIsKept() {
    var result = check.evaluate(terms("Selling price", "SELLING_PRICE", 3000000, "VIN Etch", "ADD_ON", 29900,
        "Nitrogen Tires", "ADD_ON", 19900, "Mkt Adj", "DEALER_FEE", 300000, "Sales tax", "TAX", 180000), false, null);
    assertThat(result.lines()).extracting(DealerAddOnCheck.Line::label).containsExactly("VIN Etch", "Nitrogen Tires", "Mkt Adj");
    assertThat(result.optionalTotalMinor()).isEqualTo(49800);
    assertThat(result.summary()).contains("3 named add-ons and dealer charges", "Usually optional items total $498");
    assertThat(result.lines().get(2).optional()).isFalse();
    assertThat(result.lines()).allMatch(DealerAddOnCheck.Line::verified);
  }

  @Test void anUnrecognisedDealerChargeIsListedAndNeverHidden() {
    var result = check.evaluate(terms("Gold Package", "ADD_ON", 99500, "Peace of Mind Plan", "DEALER_FEE", 59500), false, null);
    assertThat(result.lines()).hasSize(2).allMatch(line -> line.status().equals("UNRECOGNISED"));
    assertThat(result.lines().getFirst().note()).isEqualTo("Unrecognised charge — ask what it is.");
    assertThat(result.lines().getFirst().question()).isEqualTo("What is the “Gold Package”, and is it optional?");
    assertThat(result.unrecognisedTotalMinor()).isEqualTo(159000);
    assertThat(result.summary()).contains("2 unrecognised charges", "$1,590");
    var unread = terms("Gold Package", "ADD_ON", 1);
    ((ObjectNode) unread.path("lineItems").get(0)).putNull("amountMinor");
    var kept = check.evaluate(unread, false, null);
    assertThat(kept.lines()).hasSize(1);
    assertThat(kept.lines().getFirst().amountMinor()).isNull();
    assertThat(kept.unreadAmounts()).isEqualTo(1);
    assertThat(kept.summary()).contains("could not be read");
  }

  @Test void aSecondDestinationLineMayRepeatTheFirst() {
    assertThat(only("Destination", "DEALER_FEE").kind()).isEqualTo("MANUFACTURER_CHARGE");
    var two = check.evaluate(terms("Destination", "DEALER_FEE", 139500, "Inland freight", "DEALER_FEE", 49500), false, null);
    assertThat(two.lines()).allMatch(line -> line.kind().equals("POSSIBLE_DUPLICATE"));
  }

  @Test void aModelSuggestionHasNoEffectUntilTheBuyerConfirmsIt() {
    var suggested = terms("Gold Package", "ADD_ON", 99500);
    ((ObjectNode) suggested.path("lineItems").get(0)).put("suggestedChargeId", "paint-protection");
    var before = check.evaluate(suggested, false, null);
    assertThat(before.lines().getFirst().status()).isEqualTo("UNRECOGNISED");
    assertThat(before.optionalTotalMinor()).isZero();
    assertThat(check.nameFor(suggested.path("lineItems").get(0))).isNull();
    assertThat(json.valueToTree(before).toString()).doesNotContain("paint-protection");

    ((ObjectNode) suggested.path("lineItems").get(0)).put("chargeId", "paint-protection");
    var after = check.evaluate(suggested, false, null);
    assertThat(after.lines().getFirst().status()).isEqualTo("RECOGNISED");
    assertThat(after.lines().getFirst().confirmedByBuyer()).isTrue();
    assertThat(after.lines().getFirst().name()).isEqualTo("paint protection");
    assertThat(after.optionalTotalMinor()).isEqualTo(99500);

    var docFee = terms("Gold Package", "DEALER_FEE", 99500);
    ((ObjectNode) docFee.path("lineItems").get(0)).put("chargeId", "doc-fee");
    assertThat(check.evaluate(docFee, false, null)).as("left to the doc fee check").isNull();
  }

  @Test void theListingNoteAsksWhetherTheAdvertisedPriceIncludedTheAddOns() {
    var listing = new com.dealanalyzer.listing.capture.ListingPriceCheck.Result("listing-price-check.v1", "SAME", "l",
        java.time.LocalDate.of(2026, 10, 1), 3000000L, 3000000L, 0L, "DEALER_OFFER", true, null, null, List.of(), "", null);
    var result = check.evaluate(terms("VIN Etch", "ADD_ON", 29900, "Destination", "DEALER_FEE", 139500), false, listing);
    assertThat(result.listingNote()).contains("$30,000", "$299", "advertised price");
    assertThat(check.evaluate(terms("Gold Package", "ADD_ON", 99500), false, listing).listingNote()).isNull();
  }

  @Test void desksCanNameALineInAnotherFinding() {
    assertThat(check.nameFor(terms("VIN Etch", "ADD_ON", 1).path("lineItems").get(0))).isEqualTo("VIN etching");
    assertThat(check.nameFor(terms("Exterior Shine", "ADD_ON", 1).path("lineItems").get(0))).isEqualTo("possibly paint protection");
    assertThat(check.nameFor(terms("Sales tax", "TAX", 1).path("lineItems").get(0))).isNull();
  }

  @Test void copyStaysWithinTheClaimVocabulary() throws Exception {
    var termsOf = Class.forName("com.dealanalyzer.explanation.ClaimVocabulary").getDeclaredMethod("terms", String.class);
    termsOf.setAccessible(true);
    List<String> copy = new ArrayList<>();
    for (Boolean financed : new Boolean[] {true, false, null}) {
      var result = check.evaluate(terms("Ext. Warranty", "ADD_ON", 249500, "GAP", "ADD_ON", 89500, "Exterior Shine", "ADD_ON",
          1000, "Gold Package", "ADD_ON", 2000, "Mkt Adj", "DEALER_FEE", 300000, "Dealer Prep", "DEALER_FEE", 39900,
          "Destination", "DEALER_FEE", 1), financed, null);
      copy.add(result.summary());
      for (var line : result.lines())
        for (String text : new String[] {line.note(), line.question(), line.kindWording(), line.what()})
          if (text != null) copy.add(text);
    }
    for (var entry : catalogue.data().entries()) copy.addAll(List.of(entry.what(), entry.question()));
    for (String text : copy) {
      @SuppressWarnings("unchecked") var found = ((List<Object>) termsOf.invoke(null, text)).stream().map(Object::toString).toList();
      assertThat(found).as(text).doesNotContain("STRONG", "FAIR", "POOR", "EXACT", "AVAILABLE");
      assertThat(ProhibitedClaims.contains(text)).as(text).isFalse();
    }
  }
}

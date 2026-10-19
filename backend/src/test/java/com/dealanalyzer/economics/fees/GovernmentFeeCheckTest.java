package com.dealanalyzer.economics.fees;

import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.api.Assertions.tuple;

import com.dealanalyzer.ai.ProhibitedClaims;
import com.dealanalyzer.economics.fees.GovernmentFeeCheck.Finding;
import com.dealanalyzer.economics.fees.GovernmentFeeCheck.Result;
import com.dealanalyzer.economics.fees.GovernmentFeeSchedule.Group;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Ticket 139: each pilot state's fees ship as cited data, and paperwork fee lines are checked against them. */
class GovernmentFeeCheckTest {
  static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
  static final GovernmentFeeSchedule DATA = new GovernmentFeeSchedule(JSON);
  static final LocalDate TODAY = LocalDate.of(2026, 10, 18);

  /** Synthetic paperwork: label, category and amount in cents for each line. */
  static JsonNode paperwork(Object... lines) {
    ObjectNode terms = JSON.createObjectNode();
    var items = terms.putArray("lineItems");
    items.addObject().put("originalLabel", "Selling price").put("category", "SELLING_PRICE").put("amountMinor", 2500000)
        .put("currency", "USD");
    for (int i = 0; i < lines.length; i += 3)
      items.addObject().put("originalLabel", (String) lines[i]).put("category", (String) lines[i + 1])
          .put("amountMinor", ((Number) lines[i + 2]).longValue()).put("currency", "USD").put("buyerVerified", true);
    return terms;
  }

  static Result check(String state, Boolean financed, JsonNode terms) {
    return GovernmentFeeCheck.evaluate(DATA, state, financed, terms, true, TODAY);
  }

  static Finding only(Result result, String code) {
    assertThat(result.findings()).extracting(Finding::code).containsOnlyOnce(code);
    return result.findings().stream().filter(finding -> finding.code().equals(code)).findFirst().orElseThrow();
  }

  @Test void everyPilotStateIsEffectiveCheckedCitedAndDueForReview() {
    assertThat(DATA.version()).isEqualTo("government-fees.2026-10.v1");
    assertThat(DATA.data().states()).extracting(GovernmentFeeSchedule.State::state).containsExactly("FL", "TX", "NC");
    assertThat(DATA.data().states()).allSatisfy(state -> {
      assertThat(state.checkedOn()).isEqualTo(TODAY);
      assertThat(state.reviewOn()).isAfter(state.checkedOn());
      assertThat(state.effectiveOn()).isBeforeOrEqualTo(state.checkedOn());
      assertThat(state.fees()).allSatisfy(fee -> {
        assertThat(fee.citation()).contains("https://");
        assertThat(fee.purpose()).isNotBlank();
        assertThat(ProhibitedClaims.contains(fee.purpose())).as(fee.purpose()).isFalse();
      });
    });
    assertThat(DATA.state("GA")).isEmpty();
  }

  @Test void theSourcedAmountsAreTheOnesShipped() {
    assertThat(fee("FL", "FL_TITLE")).extracting("minMinor", "maxMinor").containsExactly(7525L, 8525L);
    assertThat(fee("FL", "FL_INITIAL_REGISTRATION").maxMinor()).isEqualTo(22500L);
    assertThat(fee("FL", "FL_REGISTRATION")).extracting("minMinor", "maxMinor").containsExactly(2610L, 8820L);
    assertThat(fee("TX", "TX_TITLE")).extracting("minMinor", "maxMinor").containsExactly(2800L, 3300L);
    assertThat(fee("TX", "TX_REGISTRATION").minMinor()).isEqualTo(5075L);
    assertThat(fee("TX", "TX_INSPECTION")).extracting("minMinor", "maxMinor").containsExactly(750L, 1675L);
    assertThat(fee("NC", "NC_TITLE")).extracting("minMinor", "maxMinor").containsExactly(6675L, 6675L);
    assertThat(fee("NC", "NC_REGISTRATION").minMinor()).isEqualTo(4625L);
    assertThat(fee("NC", "NC_PROPERTY_TAX").maxMinor()).isNull();
  }

  static GovernmentFeeSchedule.Fee fee(String state, String id) {
    return DATA.state(state).orElseThrow().fees().stream().filter(fee -> fee.id().equals(id)).findFirst().orElseThrow();
  }

  @Test void labelsNameTheFeesTheyCover() {
    assertThat(GovernmentFeeCheck.groups(DATA, "Tag, title and registration"))
        .containsExactlyInAnyOrder(Group.TITLE, Group.REGISTRATION, Group.PLATE);
    assertThat(GovernmentFeeCheck.groups(DATA, "Temp tag")).containsExactly(Group.TEMPORARY_TAG);
    assertThat(GovernmentFeeCheck.groups(DATA, "Government fees")).isEqualTo(java.util.EnumSet.allOf(Group.class));
    assertThat(GovernmentFeeCheck.groups(DATA, "Misc. fees")).isEmpty();
    assertThat(GovernmentFeeCheck.stateOf("US-FL:Miami-Dade")).isEqualTo("FL");
    assertThat(GovernmentFeeCheck.stateOf("US-TX")).isEqualTo("TX");
    assertThat(GovernmentFeeCheck.stateOf("SYNTHETIC-FIXTURE")).isNull();
  }

  // Florida

  @Test void floridaTitleWithinTheScheduleNamesWhatDecidesIt() {
    var result = check("FL", true, paperwork("Title fee", "GOVERNMENT_CHARGE", 7725));
    assertThat(result.status()).isEqualTo("CHECKED");
    var within = only(result, "WITHIN_RANGE");
    assertThat(within.severity()).isEqualTo("INFORMATIONAL");
    assertThat(within.highestMinor()).isEqualTo(10025L);
    assertThat(within.dependsOn()).contains("whether the car is new, already titled in Florida, or last titled in another state");
    assertThat(result.findings()).noneMatch(finding -> "MATERIAL".equals(finding.severity()));
    assertThat(result.summary()).isEqualTo("The state fees on this paperwork, $77.25, are within what Florida lists for them.");
  }

  @Test void floridaPaddedTagAndTitleLineIsAskedAbout() {
    var result = check("FL", false, paperwork("Tag, title and registration", "GOVERNMENT_CHARGE", 89500));
    var above = only(result, "ABOVE_SCHEDULE");
    assertThat(above.severity()).isEqualTo("MATERIAL");
    // A cash purchase records no lien, so the $2 lien fee is not counted.
    assertThat(above.highestMinor()).isEqualTo(44595L);
    assertThat(above.summary()).startsWith("“Tag, title and registration” on this paperwork is $895. "
        + "Florida's listed title, registration and plate fees add up to at most $445.95. Which apply depends on ")
        .endsWith("A dealer can also pass through a fee this schedule does not list.");
    assertThat(above.deskQuestion()).isEqualTo("Please itemize the state fees on this line.");
    assertThat(result.summary()).isEqualTo(above.summary());
  }

  @Test void floridaRegistrationRangeDependsOnTheUnknownWeight() {
    var result = check("FL", null, paperwork("Registration", "GOVERNMENT_CHARGE", 6000));
    var within = only(result, "WITHIN_RANGE");
    assertThat(within.lowestMinor()).isEqualTo(2610L);
    assertThat(within.highestMinor()).isEqualTo(31320L);
    assertThat(within.dependsOn()).contains("the car's weight and whether it is registered for 12 or 24 months",
        "whether you get a new plate or move a plate from a car you no longer have");
  }

  @Test void floridaElectronicFilingFeeIsADealerCharge() {
    var result = check("FL", null, paperwork("Electronic filing fee", "GOVERNMENT_CHARGE", 69900,
        "Title fee", "GOVERNMENT_CHARGE", 7725));
    var dealer = only(result, "DEALER_CHARGE_LABEL");
    assertThat(dealer.summary()).isEqualTo("“Electronic filing fee” ($699) is listed with the state fees, but it is "
        + "a name for a charge the dealer sets, not a Florida state fee.");
    assertThat(dealer.deskQuestion()).startsWith("Is “Electronic filing fee” a dealer fee?");
    assertThat(result.findings()).extracting(Finding::code).doesNotContain("ABOVE_SCHEDULE", "LINES_ABOVE_SCHEDULE");
  }

  @Test void floridaSalesTaxListedAsAStateFeeIsLeftToTheTaxCheck() {
    var result = check("FL", null, paperwork("Sales tax", "GOVERNMENT_CHARGE", 150000));
    assertThat(only(result, "TAX_COVERED").severity()).isEqualTo("INFORMATIONAL");
    assertThat(result.findings()).noneMatch(finding -> "MATERIAL".equals(finding.severity()));
  }

  @Test void floridaLinesThatEachFitButTogetherExceedTheScheduleAreAskedAbout() {
    var result = check("FL", null, paperwork("Registration", "GOVERNMENT_CHARGE", 30000,
        "Title", "GOVERNMENT_CHARGE", 9800, "Plate", "GOVERNMENT_CHARGE", 3400, "Tire fee", "GOVERNMENT_CHARGE", 600,
        "State fees", "GOVERNMENT_CHARGE", 5000));
    var total = only(result, "LINES_ABOVE_SCHEDULE");
    assertThat(total.amountMinor()).isEqualTo(48800L);
    assertThat(total.highestMinor()).isEqualTo(45645L);
    assertThat(total.deskQuestion()).isEqualTo("Please itemize the state fees on this paperwork.");
  }

  // Texas

  @Test void texasTitleAndRegistrationWithinTheSchedule() {
    var result = check("TX", true, paperwork("Title application fee", "GOVERNMENT_CHARGE", 3300,
        "License and registration", "GOVERNMENT_CHARGE", 9750));
    assertThat(result.findings()).noneMatch(finding -> "MATERIAL".equals(finding.severity()));
    assertThat(result.stateFeesMinor()).isEqualTo(13050L);
  }

  @Test void texasRegistrationAboveTheHighestPossibleAmount() {
    var result = check("TX", true, paperwork("Registration fee", "GOVERNMENT_CHARGE", 65000));
    var above = only(result, "ABOVE_SCHEDULE");
    assertThat(above.highestMinor()).isEqualTo(57725L);
    assertThat(above.summary()).contains("Texas's listed registration fees add up to at most $577.25");
  }

  @Test void texasCountyFeeDependsOnTheUnknownCounty() {
    var result = check("TX", true, paperwork("County road and bridge fee", "GOVERNMENT_CHARGE", 1000));
    assertThat(only(result, "WITHIN_RANGE").dependsOn())
        .contains("your county ($0 to $21.50 a year in 2026) and whether the car gets 24 months of registration");
  }

  @Test void texasLumpTagTitleLinesReadAsUnknownAreStillChecked() {
    var result = check("TX", true, paperwork("TT&L", "UNKNOWN", 120000, "Tag & title", "UNKNOWN", 95000));
    assertThat(result.stateFeesMinor()).isEqualTo(215000L);
    assertThat(result.findings()).filteredOn(finding -> finding.code().equals("ABOVE_SCHEDULE"))
        .extracting(Finding::label, Finding::highestMinor)
        .containsExactlyInAnyOrder(tuple("TT&L", 64025L), tuple("Tag & title", 64025L));
  }

  @Test void texasDocFeeListedAsAStateFeeIsADealerCharge() {
    var result = check("TX", true, paperwork("Doc fee", "GOVERNMENT_CHARGE", 22500));
    assertThat(only(result, "DEALER_CHARGE_LABEL").summary()).endsWith("not a Texas state fee.");
  }

  @Test void texasProcessingAndHandlingIsAStateFeeNotADealerCharge() {
    var result = check("TX", true, paperwork("Processing and handling fee", "GOVERNMENT_CHARGE", 475));
    assertThat(result.findings()).extracting(Finding::code).containsExactly("WITHIN_RANGE");
  }

  // North Carolina

  @Test void northCarolinaTitleWithinTheSchedule() {
    var result = check("NC", false, paperwork("Title", "GOVERNMENT_CHARGE", 6675));
    // The $66.75 title fee is fixed; only the one-day title service could add to it.
    var within = only(result, "WITHIN_RANGE");
    assertThat(within.dependsOn()).containsExactly("whether the one-day title service is used");
    assertThat(within.summary()).isEqualTo("“Title” is $66.75, within the most North Carolina lists for title fees, "
        + "$192.75. Which apply depends on whether the one-day title service is used.");
  }

  @Test void northCarolinaRegistrationAboveTheHighestPossibleAmount() {
    var result = check("NC", false, paperwork("Registration", "GOVERNMENT_CHARGE", 70000));
    assertThat(only(result, "ABOVE_SCHEDULE").highestMinor()).isEqualTo(38350L);
  }

  @Test void northCarolinaRegistrationRangeDependsOnTheUnknownVehicleType() {
    var result = check("NC", false, paperwork("Registration", "GOVERNMENT_CHARGE", 8300));
    assertThat(only(result, "WITHIN_RANGE").dependsOn())
        .contains("whether the vehicle is a passenger vehicle ($46.25) or a pickup, whose fee goes by its declared weight");
  }

  @Test void northCarolinaHighwayUseTaxIsNotCountedAgain() {
    var result = check("NC", true, paperwork("Highway use tax", "GOVERNMENT_CHARGE", 75000,
        "Tag and title", "GOVERNMENT_CHARGE", 18000));
    assertThat(only(result, "TAX_COVERED").summary())
        .isEqualTo("“Highway use tax” is a tax the tax check covers, so it is not counted again with the state fees.");
    assertThat(result.findings()).extracting(Finding::code).doesNotContain("ABOVE_SCHEDULE", "LINES_ABOVE_SCHEDULE");
  }

  @Test void northCarolinaLumpLineNamesThePropertyTaxItCannotCount() {
    var result = check("NC", true, paperwork("Tag, title and taxes", "GOVERNMENT_CHARGE", 90000));
    var above = only(result, "ABOVE_SCHEDULE");
    assertThat(above.highestMinor()).isEqualTo(62725L);
    assertThat(above.notCounted()).containsExactly("county vehicle property tax");
    assertThat(above.summary()).contains("add up to at most $627.25, not counting the county vehicle property tax");
  }

  @Test void northCarolinaPropertyTaxLineHasNoTopAmount() {
    var result = check("NC", true, paperwork("Vehicle property tax", "GOVERNMENT_CHARGE", 31200));
    assertThat(only(result, "NO_TOP_AMOUNT").summary()).contains("the car's value and your county and city tax rates");
  }

  @Test void northCarolinaOnlineRegistrationFeeIsADealerCharge() {
    var result = check("NC", true, paperwork("Online registration fee", "GOVERNMENT_CHARGE", 19900));
    assertThat(only(result, "DEALER_CHARGE_LABEL").summary()).endsWith("not a North Carolina state fee.");
  }

  // Not checked

  @Test void aStateWithNoScheduleMakesNoFindingAndSaysTheFeesWereNotChecked() {
    var result = check("GA", true, paperwork("Tag and title", "GOVERNMENT_CHARGE", 99900));
    assertThat(result.status()).isEqualTo("NOT_CHECKED");
    assertThat(result.findings()).isEmpty();
    assertThat(result.highestMinor()).isNull();
    assertThat(result.summary()).isEqualTo("otdog has no fee schedule for GA, so the $999 in state fees on this paperwork was not checked.");
    assertThat(check(null, true, paperwork("Tag and title", "GOVERNMENT_CHARGE", 99900)).summary())
        .contains("no fee schedule for your state");
  }

  @Test void aScheduleDueForReviewIsNotUsed() {
    var result = GovernmentFeeCheck.evaluate(DATA, "FL", true, paperwork("Tag and title", "GOVERNMENT_CHARGE", 99900),
        true, LocalDate.of(2027, 1, 2));
    assertThat(result.status()).isEqualTo("NOT_CHECKED");
    assertThat(result.findings()).isEmpty();
    assertThat(result.summary()).startsWith("Florida's fee schedule is due for review");
  }

  @Test void paperworkWithoutAStateFeeHasNoCheck() {
    assertThat(check("FL", true, paperwork("Doc fee", "DEALER_FEE", 99900))).isNull();
    assertThat(check("FL", true, null)).isNull();
  }

  @Test void unverifiedPaperworkIsNeverReportedAsVerified() {
    var result = GovernmentFeeCheck.evaluate(DATA, "FL", true, paperwork("Tag and title", "GOVERNMENT_CHARGE", 99900),
        false, TODAY);
    assertThat(result.findings()).allSatisfy(finding -> assertThat(finding.verified()).isFalse());
  }

  @Test void everyFindingIsWordedWithoutAProhibitedClaim() {
    for (var terms : List.of(paperwork("Tag, title and registration", "GOVERNMENT_CHARGE", 89500),
        paperwork("Electronic filing fee", "GOVERNMENT_CHARGE", 69900), paperwork("Sales tax", "GOVERNMENT_CHARGE", 1),
        paperwork("Tire fee", "GOVERNMENT_CHARGE", 600), paperwork("Vehicle property tax", "GOVERNMENT_CHARGE", 31200)))
      for (String state : List.of("FL", "TX", "NC", "GA")) {
        var result = check(state, null, terms);
        assertThat(ProhibitedClaims.contains(result.summary())).as(result.summary()).isFalse();
        for (var finding : result.findings()) {
          assertThat(ProhibitedClaims.contains(finding.summary())).as(finding.summary()).isFalse();
          if (finding.deskQuestion() != null) assertThat(ProhibitedClaims.contains(finding.deskQuestion())).isFalse();
        }
      }
  }

  // Label variants (operator note): dealers name the same fee many ways, so labels are matched normalised.

  @Test void labelVariantsAbbreviationsAndMisspellingsNameTheSameFees() {
    var titleRegistrationPlate = java.util.EnumSet.of(Group.TITLE, Group.REGISTRATION, Group.PLATE);
    for (String label : List.of("TT&L", "T.T.&L.", "T/T/L", "ttl fees", "Tag & Title", "Tag/Title/Reg", "TAG, TITLE, LIC.",
        "Title, tag and license", "Titel and registeration", "Tag title license"))
      assertThat(GovernmentFeeCheck.groups(DATA, label)).as(label).containsAll(java.util.EnumSet.of(Group.TITLE, Group.REGISTRATION))
          .isSubsetOf(java.util.EnumSet.allOf(Group.class));
    assertThat(GovernmentFeeCheck.groups(DATA, "TT&L")).isEqualTo(titleRegistrationPlate);
    for (String label : List.of("Registration", "Regis. fee", "Reg fee", "REGISTRATION FEES", "Registrations", "Regestration",
        "Licence fee", "Liscense", "Lic", "Inspection fee", "Insp.", "Smog fee", "Emissions", "County fee", "Local fees",
        "Road & bridge", "EV fee", "Electric vehicle fee"))
      assertThat(GovernmentFeeCheck.groups(DATA, label)).as(label).contains(Group.REGISTRATION);
    for (String label : List.of("Plate", "License plates", "Plate transfer", "Decal"))
      assertThat(GovernmentFeeCheck.groups(DATA, label)).as(label).contains(Group.PLATE);
    for (String label : List.of("Title", "Title fee", "Titling", "Tittle fee", "Lien fee", "Lein recording"))
      assertThat(GovernmentFeeCheck.groups(DATA, label)).as(label).contains(Group.TITLE);
    for (String label : List.of("Temp tag", "Temporary tag", "Paper tag", "Buyer's tag"))
      assertThat(GovernmentFeeCheck.groups(DATA, label)).as(label).containsExactly(Group.TEMPORARY_TAG);
    for (String label : List.of("Tire fee", "Tires fee", "Tyre fee", "Battery fee", "Batt. fee", "Waste tire fee", "Lead-acid battery"))
      assertThat(GovernmentFeeCheck.groups(DATA, label)).as(label).containsExactly(Group.ENVIRONMENTAL);
    for (String label : List.of("DMV fees", "D.M.V.", "State fees", "Gov't fees", "Government fees", "Tax collector fees", "MVD"))
      assertThat(GovernmentFeeCheck.groups(DATA, label)).as(label).isEqualTo(java.util.EnumSet.allOf(Group.class));
    for (String label : List.of("Service contract", "Nitrogen", "Window etch", "Selling price", "Mystery protection"))
      assertThat(GovernmentFeeCheck.groups(DATA, label)).as(label).isEmpty();
  }

  @Test void dealerChargeVariantsAreRecognised() {
    for (String label : List.of("E-filing fee", "E-File", "eFiling", "Electronic filing", "Elec. filing fee", "Doc fee",
        "Docs", "Documentary fee", "Documentation", "Admin fee", "Administrative fee", "Dealer fee", "Pre-delivery"))
      assertThat(only(check("FL", null, paperwork(label, "GOVERNMENT_CHARGE", 49900)), "DEALER_CHARGE_LABEL").label())
          .isEqualTo(label);
    assertThat(only(check("FL", null, paperwork("Electronic registration fee", "GOVERNMENT_CHARGE", 39900)),
        "DEALER_CHARGE_LABEL").summary()).endsWith("not a Florida state fee.");
  }

  @Test void aLabelNamingBothADealerChargeAndAStateFeeIsAskedAboutNotSummed() {
    var result = check("FL", null, paperwork("Dealer title & tag fee", "GOVERNMENT_CHARGE", 30000,
        "E-file / registration", "GOVERNMENT_CHARGE", 25000));
    assertThat(result.findings()).extracting(Finding::code).containsOnly("MIXED_LABEL");
    var mixed = result.findings().getFirst();
    assertThat(mixed.severity()).isEqualTo("MATERIAL");
    assertThat(mixed.summary()).isEqualTo("“Dealer title & tag fee” ($300) names both a dealer charge and a state fee, "
        + "so part of it may be a charge the dealer sets rather than a Florida state fee.");
    assertThat(mixed.deskQuestion()).isEqualTo("Please show the dealer's charge and the state fees on “Dealer title & tag fee” separately.");
  }

  @Test void aStateFeeLineThatNamesNoFeeIsAskedAboutNotIgnored() {
    var result = check("TX", true, paperwork("Misc. fees", "GOVERNMENT_CHARGE", 20000));
    var within = only(result, "WITHIN_RANGE");
    assertThat(within.summary()).startsWith("“Misc. fees” does not say which state fees it covers. Its amount, $200, is within");
    assertThat(within.deskQuestion()).isEqualTo("Please itemize the state fees on this line.");
  }

  @Test void aStateFeeLineWhoseAmountCannotBeReadIsAskedAboutNotDropped() {
    ObjectNode terms = (ObjectNode) paperwork("Tag & title", "GOVERNMENT_CHARGE", 1);
    ((ObjectNode) terms.path("lineItems").get(1)).putNull("amountMinor");
    var result = check("NC", true, terms);
    var unread = only(result, "NO_AMOUNT");
    assertThat(unread.summary()).isEqualTo("“Tag & title” is listed as a state fee, but its amount could not be read, so it was not checked.");
    assertThat(unread.deskQuestion()).isEqualTo("What is the amount of “Tag & title”, and which state fees does it include?");
  }

  @Test void aStateFeeNameListedWithTheDealersChargesIsCheckedToo() {
    var result = check("FL", null, paperwork("Title & registration", "DEALER_FEE", 95000, "Doc fee", "DEALER_FEE", 99900));
    assertThat(result.findings()).extracting(Finding::code).containsExactlyInAnyOrder("ABOVE_SCHEDULE", "LISTED_AS_DEALER_CHARGE");
    assertThat(only(result, "LISTED_AS_DEALER_CHARGE").deskQuestion())
        .isEqualTo("Is “Title & registration” a state fee the dealer passes through, or the dealer's own charge?");
    assertThat(result.stateFeesMinor()).as("the doc fee stays with the dealer's charges").isEqualTo(95000L);
  }

  /** Ticket 142: a dealer charge the add-on catalogue names is not read as a state fee for a word in its name. */
  @Test void aNamedAddOnIsNotCheckedAsAStateFee() {
    var catalogue = new DealerAddOnCatalogue(JSON);
    for (String label : List.of("Tire & Wheel", "N2 Tires", "Theft Registration", "Addendum sticker"))
      assertThat(GovernmentFeeCheck.evaluate(DATA, catalogue, "FL", true, paperwork(label, "DEALER_FEE", 29900), true, TODAY))
          .as(label).isNull();
    assertThat(GovernmentFeeCheck.evaluate(DATA, catalogue, "FL", true, paperwork("Tire fee", "DEALER_FEE", 200), true, TODAY))
        .as("a state fee's own name is still checked").isNotNull();
  }
}

package com.dealanalyzer.quote;

import static org.assertj.core.api.Assertions.*;
import com.dealanalyzer.verification.persistence.DealerOfferPersistence.DealerOfferView;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WrittenQuoteEvidenceTest {
  private final ObjectMapper json = new ObjectMapper();
  private ObjectNode terms() {
    var terms = json.createObjectNode();
    var candidates = terms.putArray("candidates");
    for (String field : java.util.List.of("SELLING_PRICE", "OUT_THE_DOOR_TOTAL", "TAX_TOTAL", "GOVERNMENT_CHARGES_TOTAL", "DEALER_CHARGES_TOTAL", "ADD_ON_TOTAL"))
      candidates.addObject().put("field", field).put("value", field.equals("SELLING_PRICE") || field.equals("OUT_THE_DOOR_TOTAL") ? "2500000" : "0")
          .put("unit", "USD_MINOR").put("buyerVerified", true);
    terms.putArray("lineItems").addObject().put("category", "SELLING_PRICE").put("amountMinor", 2500000).put("currency", "USD").put("buyerVerified", true);
    return terms;
  }
  private DealerOfferView offer(ObjectNode terms, String status) {
    return new DealerOfferView("offer", "case", "extraction", 1, status, "v1", "fingerprint", terms, Instant.now());
  }
  @Test void completeVerifiedItemizationIncludesExplicitZeroCharges() {
    assertThat(WrittenQuoteEvidence.itemized(offer(terms(), "VERIFIED"))).isTrue();
    assertThat(WrittenQuoteEvidence.itemized(offer(terms(), "DRAFT"))).isFalse();
  }
  @Test void dealerTextUsesQuoteFactsAndUtcReceiptDateWithoutInternalIdentifiers() {
    var evidence = new WrittenQuoteEvidence.Evidence("internal-message-id", "internal-document-id", offer(terms(), "VERIFIED"),
        "1HGCM82633A004352", Instant.parse("2026-10-05T00:15:00Z"));
    assertThat(evidence.text()).isEqualTo("I accept your itemized quote for VIN 1HGCM82633A004352 with an out-the-door total of USD 25000.00, received on 2026-10-05 (UTC), subject to a test drive and the final paperwork matching that quote.")
        .doesNotContain(evidence.messageId(), evidence.documentId(), "verified offer version", "will buy", "deposit", "commit to");
  }
  @Test void absentChargeTotalsUnknownItemsUnverifiedValuesAndDuplicateFieldsStayPartial() {
    var missing = terms(); missing.withArray("candidates").remove(5);
    assertThat(WrittenQuoteEvidence.itemized(offer(missing, "VERIFIED"))).isFalse();
    var unknown = terms(); ((ObjectNode) unknown.path("lineItems").get(0)).put("category", "UNKNOWN");
    assertThat(WrittenQuoteEvidence.itemized(offer(unknown, "VERIFIED"))).isFalse();
    var unverified = terms(); ((ObjectNode) unverified.path("candidates").get(0)).put("buyerVerified", false);
    assertThat(WrittenQuoteEvidence.itemized(offer(unverified, "VERIFIED"))).isFalse();
    var duplicate = terms(); duplicate.withArray("candidates").add(duplicate.path("candidates").get(0).deepCopy());
    assertThat(WrittenQuoteEvidence.itemized(offer(duplicate, "VERIFIED"))).isFalse();
    var empty = terms(); empty.withArray("lineItems").removeAll();
    assertThat(WrittenQuoteEvidence.itemized(offer(empty, "VERIFIED"))).isFalse();
  }
  @Test void unlinkedJourneyDoesNotInventOptionsOrAllowOtherCaseChanges() {
    JourneyAgreement seam = caseId -> new JourneyAgreement.Result(false, java.util.List.of());
    assertThat(seam.agree("case").journeyLinked()).isFalse();
    assertThat(seam.agree("case").otherOpenNegotiations()).isEmpty();
    assertThatThrownBy(() -> seam.choose("case", "other", JourneyAgreement.Action.STOP)).isInstanceOf(IllegalArgumentException.class);
  }
}

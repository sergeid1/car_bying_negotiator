package com.dealanalyzer.quote;

import com.dealanalyzer.verification.persistence.DealerOfferPersistence;
import com.dealanalyzer.verification.persistence.DealerOfferPersistence.DealerOfferView;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Only a frozen, itemized offer from this dealer's received conversation authorizes acceptance. */
@Component
public class WrittenQuoteEvidence {
  private final JdbcTemplate jdbc;
  private final DealerOfferPersistence offers;
  public WrittenQuoteEvidence(JdbcTemplate jdbc, DealerOfferPersistence offers) {
    this.jdbc = jdbc; this.offers = offers;
  }
  public record Evidence(String messageId, String documentId, DealerOfferView offer, String vin, Instant receivedAt) {
    public String text() {
      com.dealanalyzer.vehicle.Vin.validated(vin);
      return "The Buyer accepts your itemized quote for VIN " + vin + " with an out-the-door total of USD "
          + BigDecimal.valueOf(amount(offer.terms(), "OUT_THE_DOOR_TOTAL"), 2).toPlainString()
          + ", received on " + receivedAt.atZone(ZoneOffset.UTC).toLocalDate()
          + " (UTC), subject to a test drive and the final paperwork matching that quote.";
    }
  }
  public static final String CONFIRMATION = "Could you confirm the proposed figure in a current itemized written offer, including the selling price, taxes, all fees and all add-ons?";

  public Optional<Evidence> latest(String caseId, String dealerId) {
    return offers.latestOffer(caseId).filter(WrittenQuoteEvidence::itemized).flatMap(offer -> {
      var rows = jdbc.query("""
          select m.id, a.document_id, v.vin, m.created_at from dealer_relay_attachments a
          join vehicles v on v.case_id = a.case_id and v.vin is not null
          join dealer_relay_messages m on m.id = a.message_id and m.case_id = a.case_id
          join document_extractions e on e.document_id = a.document_id and e.case_id = a.case_id
          where a.case_id = ? and e.id = ? and a.outcome = 'ACCEPTED'
            and e.suspected_missing_pages = false
            and m.direction = 'INBOUND' and m.state = 'RECEIVED'
            and exists (select 1 from quote_requests q join outbound_drafts d on d.id = q.draft_id
              where q.case_id = a.case_id and d.dealer_id = ? and q.state = 'SENT'
                and q.dealer_address = m.dealer_address)
          order by a.created_at, a.id
          """, (r, n) -> new Evidence(r.getString(1), r.getString(2), offer, r.getString(3), r.getTimestamp(4).toInstant()),
          caseId, offer.extractionId(), dealerId);
      return rows.stream().findFirst();
    });
  }
  static boolean itemized(DealerOfferView offer) {
    if (!"VERIFIED".equals(offer.status())) return false;
    JsonNode lines = offer.terms().path("lineItems");
    if (!lines.isArray() || lines.isEmpty()) return false;
    var fields = new java.util.HashSet<String>();
    for (JsonNode term : offer.terms().path("candidates"))
      if (!fields.add(term.path("field").asText())) return false;
    for (JsonNode line : lines) {
      if (!line.path("buyerVerified").asBoolean() || !line.path("amountMinor").isIntegralNumber()
          || !"USD".equals(line.path("currency").asText())
          || !List.of("SELLING_PRICE", "DISCOUNT", "REBATE", "DEALER_FEE", "ADD_ON", "TAX",
              "GOVERNMENT_CHARGE", "TRADE", "CREDIT").contains(line.path("category").asText())) return false;
    }
    for (var component : java.util.Map.of("TAX", "TAX_TOTAL", "GOVERNMENT_CHARGE", "GOVERNMENT_CHARGES_TOTAL",
        "DEALER_FEE", "DEALER_CHARGES_TOTAL", "ADD_ON", "ADD_ON_TOTAL").entrySet()) {
      boolean present = false;
      for (JsonNode line : lines) if (component.getKey().equals(line.path("category").asText())) present = true;
      if (!present && amount(offer.terms(), component.getValue()) == null) return false;
    }
    return amount(offer.terms(), "SELLING_PRICE") != null && amount(offer.terms(), "OUT_THE_DOOR_TOTAL") != null;
  }
  public static Long amount(JsonNode terms, String field) {
    Long amount = null;
    for (JsonNode value : terms.path("candidates")) {
      if (!field.equals(value.path("field").asText())) continue;
      if (amount != null || !value.path("buyerVerified").asBoolean() || !"USD_MINOR".equals(value.path("unit").asText())) return null;
      try { amount = Long.parseLong(value.path("value").asText()); }
      catch (NumberFormatException invalid) { return null; }
      if (amount < 0) return null;
    }
    return amount;
  }
}

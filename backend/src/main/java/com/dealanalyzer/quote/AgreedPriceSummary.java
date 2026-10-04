package com.dealanalyzer.quote;

import com.dealanalyzer.buyingcase.ResourceNotFoundException;
import com.dealanalyzer.verification.persistence.DealerOfferPersistence;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

/** A bounded one-page print layout. Every financial value comes from the accepted immutable offer. */
@Service
public class AgreedPriceSummary {
  private final AgreedPriceService prices;
  private final DealerOfferPersistence offers;
  private final JdbcTemplate jdbc;
  public AgreedPriceSummary(AgreedPriceService prices, DealerOfferPersistence offers, JdbcTemplate jdbc) {
    this.prices = prices; this.offers = offers; this.jdbc = jdbc;
  }
  @Transactional
  public String download(String caseId, String token) {
    var agreed = prices.view(caseId, token).agreedPrice();
    if (agreed == null) throw new ResourceNotFoundException("Agreed Price not found");
    var offer = offers.requireOffer(caseId, agreed.offerVersion());
    String vin = jdbc.queryForList("select vin from vehicles where case_id = ?", String.class, caseId).stream()
        .filter(java.util.Objects::nonNull).findFirst().orElse("See the written quote for Vehicle identity");
    var body = new StringBuilder("<h1>Agreed Price</h1><p>Accepted subject to a test drive and final paperwork matching the written quote.</p><dl>");
    row(body, "Vehicle VIN", vin);
    row(body, "Accepted by", agreed.actor());
    row(body, "Accepted at (UTC)", agreed.acceptedAt().toString());
    row(body, "Dealer Offer version", Integer.toString(agreed.offerVersion()));
    row(body, "Approved Baseline version", Integer.toString(agreed.baselineVersion()));
    row(body, "Selling price", money(WrittenQuoteEvidence.amount(offer.terms(), "SELLING_PRICE")));
    row(body, "Out-the-door total", money(WrittenQuoteEvidence.amount(offer.terms(), "OUT_THE_DOOR_TOTAL")));
    body.append("</dl><h2>Itemized quote totals by category</h2><dl>");
    Map<String, Long> totals = new TreeMap<>();
    for (JsonNode line : offer.terms().path("lineItems"))
      totals.merge(line.path("category").asText(), line.path("amountMinor").asLong(), Math::addExact);
    for (var fallback : Map.of("TAX", "TAX_TOTAL", "GOVERNMENT_CHARGE", "GOVERNMENT_CHARGES_TOTAL",
        "DEALER_FEE", "DEALER_CHARGES_TOTAL", "ADD_ON", "ADD_ON_TOTAL", "DISCOUNT", "DISCOUNT_TOTAL", "REBATE", "REBATE_TOTAL").entrySet()) {
      Long amount = WrittenQuoteEvidence.amount(offer.terms(), fallback.getValue());
      if (amount != null) totals.putIfAbsent(fallback.getKey(), amount);
    }
    totals.forEach((category, total) -> row(body, category.replace('_', ' '), money(total)));
    body.append("</dl><h2>Other recorded terms</h2><dl>");
    for (String field : java.util.List.of("TRADE_ALLOWANCE", "TRADE_PAYOFF", "CASH_DOWN", "AMOUNT_FINANCED",
        "APR", "TERM_MONTHS", "MONTHLY_PAYMENT", "FINANCE_CHARGE", "TOTAL_OF_PAYMENTS")) {
      for (JsonNode term : offer.terms().path("candidates")) {
        if (field.equals(term.path("field").asText()) && term.path("buyerVerified").asBoolean())
          row(body, field.replace('_', ' '), term.path("unit").asText().equals("USD_MINOR")
              ? money(Long.parseLong(term.path("value").asText())) : term.path("value").asText() + " " + term.path("unit").asText());
      }
    }
    body.append("</dl><h2>Written evidence</h2><dl>");
    row(body, "Conversation message", agreed.evidenceMessageId());
    row(body, "Source document", agreed.evidenceDocumentId());
    body.append("</dl><p>Bring the full written quote with this summary. Compare the Vehicle, each fee and add-on, financing terms, and total against the dealership paperwork. This summary groups line items by category; the written quote retains each individual item.</p>");
    return "<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>Agreed Price summary</title><style>"
        + "@page{size:letter;margin:12mm}*{box-sizing:border-box}body{font:12px/1.3 system-ui,sans-serif;color:#111;background:#fff;max-width:180mm;margin:20px auto;padding:8px}"
        + "h1{font-size:24px;margin:0 0 8px}h2{font-size:15px;margin:12px 0 5px}p{margin:6px 0}dl{margin:0}dl div{display:flex;gap:12px;padding:2px 0;border-bottom:1px solid #ddd;break-inside:avoid}"
        + "dt{flex:0 0 45%}dd{margin:0;overflow-wrap:anywhere}@media print{body{margin:0;padding:0}}"
        + "</style><body>" + body + "</body></html>";
  }
  private static String money(long minor) { return "USD " + BigDecimal.valueOf(minor, 2).toPlainString(); }
  private static void row(StringBuilder body, String label, String value) {
    body.append("<div><dt>").append(HtmlUtils.htmlEscape(label)).append("</dt><dd>")
        .append(HtmlUtils.htmlEscape(value)).append("</dd></div>");
  }
}

package com.dealanalyzer.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;

/** Domain codes and typed parameters for compound findings whose English prose is intentionally retained. */
final class StructuredFindings {
  private StructuredFindings() {}
  static void annotate(JsonNode analysis, String root, ObjectNode out, ObjectMapper json) {
    if (analysis.path("dealReview").isObject()) annotate(analysis.path("dealReview"), root + "/dealReview", out, json);
    addOns(analysis, root, out, json);
    trade(analysis, root, out, json);
    int amountIndex = 0;
    for (var amount : analysis.path("materialAmounts")) {
      if (amount.path("pointer").asText().matches("/result/lineItems/[0-9]+/amount"))
        put(out, root + "/materialAmounts/" + amountIndex + "/label", finding("AMOUNT_DOCUMENT", Map.of("label", amount.path("label").asText()), amount.path("label").asText()), json);
      else {
        String code = amountCode(amount.path("pointer").asText());
        if (code != null) put(out, root + "/materialAmounts/" + amountIndex + "/label", finding(code, Map.of(), amount.path("label").asText()), json);
      }
      amountIndex++;
    }
    String feesKey = analysis.has("governmentFeeCheck") ? "governmentFeeCheck" : "governmentFees";
    var fees = analysis.path(feesKey);
    String path = root + "/" + feesKey;
    String version = fees.path("scheduleVersion").asText();
    if (fees.isObject()) {
      var summary = finding("CHECKED".equals(fees.path("status").asText()) ? "STATE_FEE_CHECKED" : "STATE_FEE_NOT_CHECKED",
          Map.of("amount", money(fees.path("stateFeesMinor")), "ruleVersion", version), fees.path("summary").asText());
      int i = 0;
      for (var fee : fees.path("findings")) {
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("label", fee.path("label").asText());
        parameters.put("amount", money(fee.path("amountMinor")));
        parameters.put("highest", money(fee.path("highestMinor")));
        parameters.put("ruleVersion", version);
        var text = finding("STATE_FEE_" + fee.path("code").asText(), parameters, fee.path("summary").asText());
        put(out, path + "/findings/" + i + "/summary", text, json);
        if (fee.path("deskQuestion").isTextual()) put(out, path + "/findings/" + i + "/deskQuestion",
            finding("STATE_FEE_QUESTION", parameters, fee.path("deskQuestion").asText()), json);
        if (fee.path("summary").asText().equals(fees.path("summary").asText())) summary = text;
        i++;
      }
      put(out, path + "/summary", summary, json);
    }
    int i = 0;
    for (var fee : analysis.path("docFeeFindings")) {
      docFee(fee, root + "/docFeeFindings/" + i++, out, json);
    }
    for (String list : java.util.List.of("findings", "otherDifferences")) {
      i = 0;
      for (var fee : analysis.path(list)) {
        if (fee.path("kind").asText().equals("DOC_FEE_CHECK") || fee.path("code").asText().equals("DOC_FEE_CHECK"))
          docFee(fee, root + "/" + list + "/" + i, out, json);
        i++;
      }
    }
    firstResult(analysis, root, out, json);
  }
  private static void firstResult(JsonNode analysis, String root, ObjectNode out, ObjectMapper json) {
    if (!analysis.has("lead")) return;
    String addOnsKey = analysis.has("dealerAddOns") ? "dealerAddOns" : "addOns";
    int index = 0;
    for (var item : analysis.path("findings")) {
      String path = root + "/findings/" + index;
      String code = item.path("code").asText();
      int lineIndex = 0;
      for (var line : analysis.path(addOnsKey).path("lines")) {
        String label = line.path("label").asText();
        if (code.equals("ADD_ON") && item.path("summary").asText().startsWith(label + ",")) {
          var name = out.path(root + "/" + addOnsKey + "/lines/" + lineIndex + "/name");
          if (name.isObject()) {
            Object named = json.convertValue(name, FindingText.Finding.class);
            if (line.path("status").asText().equals("POSSIBLE")) named = finding("DESK_POSSIBLE_NAME", Map.of("name", named), "");
            var kind = FindingText.identify(line.path("kindWording").asText());
            put(out, path + "/summary", finding("DESK_NAMED_ADDON", Map.of("label", label,
                "amount", money(line.path("amountMinor")), "name", named,
                "kind", kind == null ? line.path("kindWording").asText() : kind), item.path("summary").asText()), json);
          }
        }
        if (code.equals("ADDED_CHARGE") && item.path("summary").asText().startsWith("Added: " + label))
          put(out, path + "/summary", finding("DESK_ADDED_CHARGE", Map.of("label", label,
              "amount", money(line.path("amountMinor"))), item.path("summary").asText()), json);
        lineIndex++;
      }
      int tradeIndex = 0;
      for (var trade : analysis.path("trade").path("findings")) {
        if (trade.path("code").asText().equals(code)) {
          var text = out.path(root + "/trade/findings/" + tradeIndex + "/summary");
          if (text.isObject()) out.set(path + "/summary", text.deepCopy());
        }
        tradeIndex++;
      }
      if (!out.has(path + "/summary")) {
        var entries = new java.util.ArrayList<Map.Entry<String, JsonNode>>(); out.fields().forEachRemaining(entries::add);
        for (var entry : entries) if (entry.getValue().path("text").asText().equals(item.path("summary").asText())) {
          out.set(path + "/summary", entry.getValue().deepCopy()); break;
        }
      }
      if (index == 0) for (String field : java.util.List.of("summary", "deskQuestion")) {
        var text = out.get(path + "/" + field);
        if (text != null) out.set(root + "/lead/" + field, text.deepCopy());
      }
      index++;
    }
  }
  private static String amountCode(String pointer) {
    return switch (pointer) {
      case "/result/vehicleSubtotal" -> "AMOUNT_VEHICLE_SUBTOTAL";
      case "/result/dealerCharges" -> "AMOUNT_DEALER_CHARGES";
      case "/result/governmentCharges" -> "AMOUNT_GOVERNMENT_CHARGES";
      case "/result/unknownCharges" -> "AMOUNT_UNKNOWN_CHARGES";
      case "/result/taxes" -> "AMOUNT_TAXES";
      case "/result/outTheDoorTotal", "/inputs/outTheDoorTotalMinor", "/analysis/economics/outTheDoorTotal" -> "AMOUNT_OTD";
      case "/result/tradeTerms/allowance" -> "AMOUNT_TRADE_ALLOWANCE";
      case "/result/tradeTerms/payoff" -> "AMOUNT_TRADE_PAYOFF";
      case "/result/tradeTerms/netCredit" -> "AMOUNT_TRADE_NET";
      case "/result/downPayment" -> "AMOUNT_DOWN_PAYMENT";
      case "/result/credits" -> "AMOUNT_CREDITS";
      case "/result/netAmountDue" -> "AMOUNT_DUE";
      case "/result/priceReductions/dealerDiscounts" -> "AMOUNT_DISCOUNTS";
      case "/result/priceReductions/verifiedRebates" -> "AMOUNT_REBATES";
      case "/result/priceReductions/conditionalRebatesExcluded" -> "AMOUNT_REBATES_EXCLUDED";
      case "/result/reconciliation/quotedTotal" -> "AMOUNT_QUOTED_TOTAL";
      case "/result/reconciliation/recomputedTotal" -> "AMOUNT_RECOMPUTED_TOTAL";
      case "/result/reconciliation/difference" -> "AMOUNT_TOTAL_DIFFERENCE";
      case "/result/reconciliation/tolerance" -> "AMOUNT_TOLERANCE";
      case "/payload/plan/sellingPrice/openingMinor" -> "AMOUNT_PLAN_SELLING_OPENING";
      case "/payload/plan/sellingPrice/targetMinor" -> "AMOUNT_PLAN_SELLING_TARGET";
      case "/payload/plan/sellingPrice/policyMaximumMinor" -> "AMOUNT_PLAN_SELLING_MAXIMUM";
      case "/payload/plan/sellingPrice/walkAwayMinor" -> "AMOUNT_PLAN_SELLING_WALKAWAY";
      case "/payload/plan/outTheDoor/openingMinor" -> "AMOUNT_PLAN_OTD_OPENING";
      case "/payload/plan/outTheDoor/targetMinor" -> "AMOUNT_PLAN_OTD_TARGET";
      case "/payload/plan/outTheDoor/policyMaximumMinor" -> "AMOUNT_PLAN_OTD_MAXIMUM";
      case "/payload/plan/outTheDoor/walkAwayMinor" -> "AMOUNT_PLAN_OTD_WALKAWAY";
      case "/payload/plan/buyerMaxSellingPriceMinor" -> "AMOUNT_BUYER_SELLING_LIMIT";
      case "/payload/plan/buyerMaxOutTheDoorPriceMinor" -> "AMOUNT_BUYER_OTD_LIMIT";
      case "/payload/plan/savings/baselineMinor" -> "AMOUNT_SAVINGS_BASELINE";
      case "/payload/plan/savings/amountMinor" -> "AMOUNT_SAVINGS_DIFFERENCE";
      default -> null;
    };
  }
  private static Object value(JsonNode node) {
    return node.isValueNode() && !node.isNull() ? node.asText() : finding("UNKNOWN", Map.of(), "Unknown");
  }
  private static Object date(JsonNode node) {
    return node.isTextual() && node.asText().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") ? Map.of("date", node.asText()) : value(node);
  }
  private static void trade(JsonNode analysis, String root, ObjectNode out, ObjectMapper json) {
    String key = analysis.has("tradeCheck") ? "tradeCheck" : "trade";
    var trade = analysis.path(key);
    if (!trade.isObject()) return;
    String path = root + "/" + key;
    var parameters = new LinkedHashMap<String, Object>();
    parameters.put("allowance", money(trade.path("allowanceMinor")));
    parameters.put("paperworkPayoff", money(trade.path("paperworkPayoffMinor")));
    parameters.put("buyerPayoff", money(trade.at("/buyerPayoff/amountMinor")));
    parameters.put("goodThrough", date(trade.at("/buyerPayoff/goodThrough")));
    parameters.put("writtenOffer", money(trade.at("/bestOffer/amountMinor")));
    parameters.put("equity", money(trade.path("equityMinor")));
    parameters.put("negative", trade.path("equityMinor").isIntegralNumber()
        ? Map.of("minorUnits", Math.abs(trade.path("equityMinor").longValue()), "currency", "USD") : value(nullNode()));
    parameters.put("low", money(trade.at("/valuation/lowMinor"))); parameters.put("high", money(trade.at("/valuation/highMinor")));
    parameters.put("source", value(trade.at("/valuation/source"))); parameters.put("checked", date(trade.at("/valuation/checkedOn")));
    parameters.put("old", trade.at("/valuation/old").isBoolean() ? finding(trade.at("/valuation/old").asBoolean() ? "YES" : "NO", Map.of(), "") : value(nullNode()));
    parameters.put("paperworkVin", value(trade.at("/vinMatch/paperworkVin"))); parameters.put("recordedVin", value(trade.at("/vinMatch/recordedVin")));
    parameters.put("aboveValue", money(trade.at("/priceTogether/allowanceAboveValueMinor")));
    parameters.put("priceIncrease", money(trade.at("/priceTogether/priceIncreaseMinor"))); parameters.put("netEffect", money(trade.at("/priceTogether/netEffectMinor")));
    parameters.put("paymentIncrease", money(trade.at("/negativeEquityCost/paymentIncreaseMinor")));
    parameters.put("scheduledIncrease", money(trade.at("/negativeEquityCost/scheduledPaymentsIncreaseMinor")));
    parameters.put("interest", money(trade.at("/negativeEquityCost/interestMinor")));
    parameters.put("taxCredit", money(trade.at("/taxCredit/creditMinor"))); parameters.put("gapAfterTax", money(trade.path("offerGapAfterTaxMinor")));
    parameters.put("ruleVersion", value(trade.at("/taxCredit/policyVersion"))); parameters.put("jurisdiction", value(trade.at("/taxCredit/jurisdiction")));
    int index = 0;
    for (var item : trade.path("findings")) {
      var values = new LinkedHashMap<>(parameters); values.put("difference", money(item.path("amountMinor")));
      var finding = finding("TRADE_" + item.path("code").asText(), values, item.path("summary").asText());
      put(out, path + "/findings/" + index + "/summary", finding, json);
      if (item.path("deskQuestion").isTextual()) put(out, path + "/findings/" + index + "/deskQuestion",
          finding("TRADE_QUESTION", values, item.path("deskQuestion").asText()), json);
      int reasonIndex = 0;
      for (var reason : analysis.path("topReasons")) {
        if (reason.asText().equals(item.path("summary").asText())) put(out, root + "/topReasons/" + reasonIndex, finding, json);
        reasonIndex++;
      }
      int tacticIndex = 0;
      for (var tactic : analysis.path("tacticFindings")) {
        if (tactic.path("tradeFindingCode").asText().equals(item.path("code").asText()))
          put(out, root + "/tacticFindings/" + tacticIndex + "/summary", finding, json);
        tacticIndex++;
      }
      index++;
    }
    if (trade.at("/negativeEquityCost/reason").isTextual()) put(out, path + "/negativeEquityCost/reason",
        finding("TRADE_COST_UNAVAILABLE", parameters, trade.at("/negativeEquityCost/reason").asText()), json);
    if (trade.at("/taxCredit/reason").isTextual()) put(out, path + "/taxCredit/reason", finding(
        trade.at("/taxCredit/status").asText().equals("AVAILABLE") ? "TRADE_TAX_CALCULATION" : "TRADE_TAX_UNKNOWN",
        parameters, trade.at("/taxCredit/reason").asText()), json);
  }
  private static JsonNode nullNode() { return com.fasterxml.jackson.databind.node.NullNode.getInstance(); }
  private static void addOns(JsonNode analysis, String root, ObjectNode out, ObjectMapper json) {
    String key = analysis.has("dealerAddOns") ? "dealerAddOns" : "addOns";
    var check = analysis.path(key);
    if (!check.isObject()) return;
    String path = root + "/" + key;
    long recognized = 0, possible = 0, unknown = 0;
    int index = 0;
    for (var line : check.path("lines")) {
      boolean suggested = line.path("status").asText().equals("POSSIBLE");
      if (suggested) possible++;
      else if (line.path("status").asText().equals("RECOGNISED")) recognized++;
      else unknown++;
      var names = new java.util.ArrayList<FindingText.Finding>();
      var descriptions = new java.util.ArrayList<FindingText.Finding>();
      for (var id : line.path("chargeIds")) {
        String prefix = "ADDON_" + id.asText().replace('-', '_').toUpperCase(java.util.Locale.ROOT);
        names.add(finding(prefix + "_NAME", Map.of(), line.path("name").asText()));
        descriptions.add(finding(prefix + "_WHAT", Map.of(), line.path("what").asText()));
      }
      if (!names.isEmpty()) {
        var parameters = Map.<String, Object>of("label", line.path("label").asText(),
            "names", Map.of("items", names, "mode", suggested ? "or" : "and"));
        put(out, path + "/lines/" + index + "/name", finding("ADDON_NAMES", parameters, line.path("name").asText()), json);
        put(out, path + "/lines/" + index + "/what", finding("ADDON_WHATS", Map.of("descriptions", Map.of("items", descriptions, "mode", "sentences")), line.path("what").asText()), json);
        if (suggested || names.size() > 1) put(out, path + "/lines/" + index + "/question",
            finding(suggested ? "ADDON_POSSIBLE_QUESTION" : "ADDON_COMBINED_QUESTION", parameters, line.path("question").asText()), json);
      }
      index++;
    }
    var parameters = new LinkedHashMap<String, Object>();
    parameters.put("recognized", recognized); parameters.put("possible", possible); parameters.put("unknown", unknown);
    parameters.put("optionalTotal", money(check.path("optionalTotalMinor")));
    parameters.put("possibleTotal", money(check.path("possibleTotalMinor")));
    parameters.put("unknownTotal", money(check.path("unrecognisedTotalMinor")));
    parameters.put("unread", check.path("unreadAmounts").asInt());
    put(out, path + "/summary", finding("ADDON_SUMMARY", parameters, check.path("summary").asText()), json);
  }
  private static void docFee(JsonNode fee, String path, ObjectNode out, ObjectMapper json) {
    var evidence = fee.has("observedEvidence") ? fee.path("observedEvidence") : fee.path("evidence");
    var rule = evidence.path("rule");
    if (!rule.isObject()) return;
    String state = rule.path("state").asText().replace("US-", "");
    var labels = new java.util.ArrayList<String>();
    for (var line : evidence.path("lines")) labels.add("«" + line.path("originalLabel").asText() + "»");
    var values = new LinkedHashMap<String, Object>();
    values.put("labels", String.join(", ", labels));
    String observed = fee.has("observed") ? "/observed" : "/desk";
    values.put("amount", fee.has("observed") || fee.has("desk") ? money(fee.at(observed + "/amountMinor")) : docTotal(evidence, "DOC_FEE"));
    values.put("possible", fee.has("observed") || fee.has("desk") ? money(fee.at(observed + "/possibleDocFeeAmountMinor")) : docTotal(evidence, "POSSIBLE_DOC_FEE"));
    values.put("ruleVersion", evidence.path("policyVersion").asText());
    values.put("checked", Map.of("date", rule.path("checkedAt").asText()));
    values.put("reviewBy", Map.of("date", rule.path("reviewBy").asText()));
    values.put("citation", rule.path("citation").asText());
    String explanationField = fee.has("reason") ? "reason" : "summary";
    var explanation = finding("DOC_FEE_" + state, values, fee.path(explanationField).asText());
    if (fee.has(explanationField)) put(out, path + "/" + explanationField, explanation, json);
    String questionField = fee.has("buyerAction") ? "buyerAction" : "deskQuestion";
    var question = finding("DOC_FEE_QUESTION", values, fee.path(questionField).asText());
    put(out, path + "/" + questionField, fee.has("kind")
        ? finding("DESK_COMBINED", Map.of("explanation", explanation, "question", question), fee.path(questionField).asText())
        : question, json);
  }
  private static Object docTotal(JsonNode evidence, String tier) {
    long total = 0;
    for (var line : evidence.path("lines")) if (line.path("recognitionTier").asText().equals(tier)) {
      if (!line.path("amountMinor").isIntegralNumber() || !line.path("currency").asText().equals("USD")) return finding("UNKNOWN", Map.of(), "Unknown");
      total = Math.addExact(total, line.path("amountMinor").longValue());
    }
    return Map.of("minorUnits", total, "currency", "USD");
  }
  private static Object money(JsonNode amount) {
    return amount.isIntegralNumber() ? Map.of("minorUnits", amount.longValue(), "currency", "USD") : finding("UNKNOWN", Map.of(), "Unknown");
  }
  private static FindingText.Finding finding(String code, Map<String, Object> parameters, String text) {
    return new FindingText.Finding(code, parameters, text);
  }
  private static void put(ObjectNode out, String path, FindingText.Finding value, ObjectMapper json) {
    out.set(path, json.valueToTree(value));
  }
}

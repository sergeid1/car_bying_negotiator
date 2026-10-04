package com.dealanalyzer.outbound;

import com.dealanalyzer.mandate.MandatePolicy.Disclosure;
import com.dealanalyzer.mandate.MandateService.Mandate;
import com.dealanalyzer.mandate.MandateService.Status;
import com.dealanalyzer.negotiation.GroundedLanguage;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** A closed language policy: unknown prose fails rather than guessing its meaning. No I/O or clock. */
public final class OutboundGate {
  public static final String VERSION = "outbound-gate.v1";
  private static final Set<String> QUESTIONS = Set.of(
      "Could you provide a current itemized written offer?",
      "Is there flexibility in the written selling price?",
      "Could you confirm the full payment schedule and full-term cost in writing?");
  private static final Pattern PRICE = Pattern.compile(
      "Would you consider ([A-Z]{3}) ([0-9]{1,9}\\.[0-9]{2}) as the selling price in a revised written offer\\?");
  private static final Pattern NUMBERS = Pattern.compile("[0-9]+(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?");
  private static final Pattern COMMITMENT = Pattern.compile(
      "(?i)\\b(buy|take it|deposit|sign|signature|hold|payment to follow|deal is done|accept|commit|purchase|reserve)\\b");

  public record Draft(String dealerId, String text) {}
  /** Only trusted callers build context. Released values are specific to draft.dealerId. */
  public record GateContext(Mandate mandate, Set<Long> verifiedAmountsMinor,
      Set<Disclosure> verifiedDisclosures, Set<String> personalValues,
      Set<String> releasedForDealer, JsonNode finalizedReport) {
    public GateContext {
      verifiedAmountsMinor = Set.copyOf(verifiedAmountsMinor);
      verifiedDisclosures = Set.copyOf(verifiedDisclosures);
      personalValues = Set.copyOf(personalValues);
      releasedForDealer = Set.copyOf(releasedForDealer);
      finalizedReport = finalizedReport == null ? null : finalizedReport.deepCopy();
    }
  }
  public record Reason(String code, String text) {}
  public record Decision(boolean pass, String gateVersion, List<Reason> reasons) {
    public Decision { reasons = List.copyOf(reasons); }
  }

  public Decision check(Draft draft, GateContext ctx) {
    var reasons = new LinkedHashMap<String, Reason>();
    String text = draft == null || draft.text() == null ? "" : draft.text();
    Mandate mandate = ctx.mandate();
    if (mandate == null || mandate.status() != Status.ACTIVE)
      add(reasons, "NO_ACTIVE_MANDATE", "Approve a current Negotiation Mandate before this message can proceed.");
    if (text.isBlank() || text.length() > 10000)
      add(reasons, "INVALID_DRAFT", "The draft must contain a message of at most 10,000 characters.");
    String folded = fold(text);
    for (String value : ctx.personalValues()) {
      if (!value.isBlank() && folded.contains(fold(value)) && !ctx.releasedForDealer().contains(value))
        add(reasons, "PERSONAL_DATA", "The draft contains your personal information that has not been released for this dealer.");
    }
    if (text.contains("@") || Pattern.compile("(?i)\\b(phone|address|ssn|social security|bank account|date of birth)\\b").matcher(text).find())
      add(reasons, "PERSONAL_DATA", "The draft includes personal contact or identity details. Remove them before review.");

    Set<String> grounded = new HashSet<>(QUESTIONS);
    if (ctx.finalizedReport() != null) {
      try {
        var language = new GroundedLanguage().generate(ctx.finalizedReport());
        language.items().forEach(item -> grounded.add(item.question()));
      } catch (IllegalArgumentException invalid) {
        // An unfinalized report authorizes no additional language.
      }
    }
    for (String paragraph : text.split("\\n\\n", -1)) {
      var price = PRICE.matcher(paragraph);
      if (price.matches()) {
        long amount = new BigDecimal(price.group(2)).movePointRight(2).longValueExact();
        if (mandate != null) {
          if (!price.group(1).equals(mandate.currency()))
            add(reasons, "CURRENCY", "The amount uses a different currency from your mandate.");
          if (amount > mandate.ceilingMinor())
            add(reasons, "ABOVE_CEILING", "The draft includes an amount above your walk-away ceiling.");
          if (amount != mandate.targetMinor() && amount != mandate.ceilingMinor()
              && !ctx.verifiedAmountsMinor().contains(amount))
            add(reasons, "UNTRACEABLE_AMOUNT", "The amount does not come from your mandate or verified case facts.");
        }
      } else if (paragraph.equals("I am paying cash.") || paragraph.equals("I have outside financing.")) {
        Disclosure disclosure = paragraph.equals("I am paying cash.") ? Disclosure.PAYING_CASH : Disclosure.HAS_OUTSIDE_FINANCING;
        if (mandate == null || !mandate.disclosures().contains(disclosure))
          add(reasons, "DISCLOSURE", "Your mandate does not permit this disclosure to the dealer.");
        if (!ctx.verifiedDisclosures().contains(disclosure))
          add(reasons, "UNGROUNDED_FACT", "This statement is not supported by verified case facts.");
      } else if (!grounded.contains(paragraph)) {
        if (COMMITMENT.matcher(paragraph).find())
          add(reasons, "COMMITMENT", "This wording may commit you to buying, paying, reserving or signing. Ask for written terms instead.");
        add(reasons, "UNSUPPORTED_LANGUAGE", "This wording cannot be checked against the approved message templates and verified facts. Revise it before review.");
        if (NUMBERS.matcher(paragraph).find() || Pattern.compile("(?i)\\b(dollars?|thousand|hundred|million|grand|bucks)\\b").matcher(paragraph).find())
          add(reasons, "UNVERIFIABLE_AMOUNT", "Use an explicit currency and decimal amount in the approved price question so every amount can be checked.");
        if (Pattern.compile("(?i)\\b(other dealer|competing|another offer|offered me|accident|warranty|guarantee)\\b").matcher(paragraph).find())
          add(reasons, "UNGROUNDED_FACT", "The draft makes a statement that is not grounded in verified case facts.");
      }
    }
    return new Decision(reasons.isEmpty(), VERSION, new ArrayList<>(reasons.values()));
  }

  private static String fold(String value) {
    return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
  }
  private static void add(Map<String, Reason> reasons, String code, String text) {
    reasons.putIfAbsent(code, new Reason(code, text));
  }
}

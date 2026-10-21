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
  public static final String VERSION = "outbound-gate.v5";
  private static final Set<String> QUESTIONS = Set.of(
      "Could you provide a current itemized written offer?",
      "Is there flexibility in the written selling price?",
      "Could you confirm the full payment schedule and full-term cost in writing?");
  private static final Pattern PRICE = Pattern.compile(
      "Would you consider ([A-Z]{3}) ([0-9]{1,9}\\.[0-9]{2}) as the selling price in a revised written offer\\?");
  private static final Pattern PRICE_ES = Pattern.compile(
      "¿Consideraría ([A-Z]{3}) ([0-9]{1,9}\\.[0-9]{2}) como precio de venta en una oferta revisada por escrito\\?");
  private static final Map<String, Map<Disclosure, String>> DISCLOSURES = Map.of(
      "en", Map.of(Disclosure.PAYING_CASH, "I am paying cash.", Disclosure.HAS_OUTSIDE_FINANCING, "I have outside financing."),
      "es", Map.of(Disclosure.PAYING_CASH, "Voy a pagar en efectivo.", Disclosure.HAS_OUTSIDE_FINANCING, "Tengo financiamiento externo."));
  private static final Pattern NUMBERS = Pattern.compile("[0-9]+(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?");
  private static final Pattern COMMITMENT = Pattern.compile(
      "(?i)\\b(buy|take it|deposit|sign|signature|hold|payment to follow|deal is done|accept|commit|purchase|reserve)\\b");

  /** Spanish keyword checks run on Spanish drafts as well as the English ones; they name reasons, never permit. */
  private static final Pattern PERSONAL_ES = palabra("tel[eé]fono|celular|direcci[oó]n|domicilio|seguro social"
      + "|cuenta (bancaria|de banco)|fecha de nacimiento");
  private static final Pattern COMMITMENT_ES = palabra("compr\\p{L}*|firm\\p{L}*|dep[oó]sit\\p{L}*|reserv\\p{L}*|apart\\p{L}*"
      + "|acept\\p{L}*|adquir\\p{L}*|me comprometo|me (lo|la) llevo|trato hecho|vendid[oa]|pago a seguir");
  private static final Pattern AMOUNT_WORDS_ES = palabra("d[oó]lar(es)?|mil|miles|cien|ciento|\\p{L}*cientos|mill[oó]n|millones"
      + "|lucas|varos|centavos?");
  private static final Pattern UNGROUNDED_ES = palabra("otr[oa]s? (concesionari[oa]s?|agencias?|ofertas?|vendedor(es)?)|competencia"
      + "|me ofrecieron|me ofrecen|accidentes?|garant\\p{L}*");

  public record Draft(String dealerId, String text) {}
  /** Only trusted callers build context. Released values are specific to draft.dealerId. */
  public record GateContext(Mandate mandate, Set<Long> verifiedAmountsMinor,
      Set<Disclosure> verifiedDisclosures, Set<String> personalValues,
      Set<String> releasedForDealer, JsonNode finalizedReport, Set<String> caseQuestions, String quoteRequestQuestion,
      String language) {
    /** An English context, as every context was before ticket 149. */
    public GateContext(Mandate mandate, Set<Long> amounts, Set<Disclosure> disclosures, Set<String> personal,
        Set<String> released, JsonNode report, Set<String> questions, String quoteRequestQuestion) {
      this(mandate, amounts, disclosures, personal, released, report, questions, quoteRequestQuestion, "en");
    }
    public GateContext(Mandate mandate, Set<Long> amounts, Set<Disclosure> disclosures,
        Set<String> personal, Set<String> released, JsonNode report, Set<String> questions) {
      this(mandate, amounts, disclosures, personal, released, report, questions, null);
    }
    public GateContext(Mandate mandate, Set<Long> amounts, Set<Disclosure> disclosures,
        Set<String> personal, Set<String> released, JsonNode report) {
      this(mandate, amounts, disclosures, personal, released, report, Set.of());
    }
    public GateContext {
      verifiedAmountsMinor = Set.copyOf(verifiedAmountsMinor);
      verifiedDisclosures = Set.copyOf(verifiedDisclosures);
      personalValues = Set.copyOf(personalValues);
      releasedForDealer = Set.copyOf(releasedForDealer);
      caseQuestions = Set.copyOf(caseQuestions);
      if (quoteRequestQuestion != null && !caseQuestions.contains(quoteRequestQuestion))
        throw new IllegalArgumentException("The quote request must be included in the grounded questions.");
      finalizedReport = finalizedReport == null ? null : finalizedReport.deepCopy();
      if (language == null || !DISCLOSURES.containsKey(language)) throw new IllegalArgumentException("Unsupported dealer email language");
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
    boolean spanish = "es".equals(ctx.language());
    if (text.contains("@") || Pattern.compile("(?i)\\b(phone|address|ssn|social security|bank account|date of birth)\\b").matcher(text).find()
        || spanish && PERSONAL_ES.matcher(text).find())
      add(reasons, "PERSONAL_DATA", "The draft includes personal contact or identity details. Remove them before review.");
    // A Spanish draft meets the claim rules the Spanish screens meet: the Spanish list and the English one (ticket 146).
    if (spanish && com.dealanalyzer.ai.ProhibitedClaims.contains(text, "es"))
      add(reasons, "PROHIBITED_CLAIM", "The draft makes a claim that messages may not make. Revise it before review.");

    Set<String> grounded = new HashSet<>();
    for (String question : QUESTIONS) grounded.add(GroundedLanguage.question(question, ctx.language()));
    grounded.addAll(ctx.caseQuestions());
    if (ctx.finalizedReport() != null) {
      try {
        var language = new GroundedLanguage().generate(ctx.finalizedReport());
        for (var item : language.items()) grounded.add(GroundedLanguage.question(item.question(), ctx.language()));
      } catch (IllegalArgumentException invalid) {
        // An unfinalized report, or a question without a counterpart, authorizes no additional language.
      }
    }
    var disclosures = DISCLOSURES.get(ctx.language());
    for (String paragraph : text.split("\\n\\n", -1)) {
      var price = (spanish ? PRICE_ES : PRICE).matcher(paragraph);
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
      } else if (disclosures.containsValue(paragraph)) {
        Disclosure disclosure = paragraph.equals(disclosures.get(Disclosure.PAYING_CASH)) ? Disclosure.PAYING_CASH : Disclosure.HAS_OUTSIDE_FINANCING;
        if (mandate == null || !mandate.disclosures().contains(disclosure))
          add(reasons, "DISCLOSURE", "Your mandate does not permit this disclosure to the dealer.");
        if (!ctx.verifiedDisclosures().contains(disclosure))
          add(reasons, "UNGROUNDED_FACT", "This statement is not supported by verified case facts.");
      } else if (!grounded.contains(paragraph)) {
        if (COMMITMENT.matcher(paragraph).find() || spanish && COMMITMENT_ES.matcher(paragraph).find())
          add(reasons, "COMMITMENT", "This wording may commit you to buying, paying, reserving or signing. Ask for written terms instead.");
        add(reasons, "UNSUPPORTED_LANGUAGE", "This wording cannot be checked against the approved message templates and verified facts. Revise it before review.");
        if (NUMBERS.matcher(paragraph).find() || Pattern.compile("(?i)\\b(dollars?|thousand|hundred|million|grand|bucks)\\b").matcher(paragraph).find()
            || spanish && AMOUNT_WORDS_ES.matcher(paragraph).find())
          add(reasons, "UNVERIFIABLE_AMOUNT", "Use an explicit currency and decimal amount in the approved price question so every amount can be checked.");
        if (Pattern.compile("(?i)\\b(other dealer|competing|another offer|offered me|accident|warranty|guarantee)\\b").matcher(paragraph).find()
            || spanish && UNGROUNDED_ES.matcher(paragraph).find())
          add(reasons, "UNGROUNDED_FACT", "The draft makes a statement that is not grounded in verified case facts.");
      }
    }
    return new Decision(reasons.isEmpty(), VERSION, new ArrayList<>(reasons.values()));
  }

  private static String fold(String value) {
    return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
  }
  /** A whole-word pattern over Unicode letters, so accented Spanish words keep their boundaries. */
  private static Pattern palabra(String alternatives) {
    return Pattern.compile("(?iu)(?<![\\p{L}\\p{N}_])(" + alternatives + ")(?![\\p{L}\\p{N}_])");
  }
  private static void add(Map<String, Reason> reasons, String code, String text) {
    reasons.putIfAbsent(code, new Reason(code, text));
  }
}

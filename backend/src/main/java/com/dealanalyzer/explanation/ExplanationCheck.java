package com.dealanalyzer.explanation;

import com.dealanalyzer.explanation.ClaimVocabulary.Subject;
import com.dealanalyzer.explanation.ClaimVocabulary.Term;
import com.dealanalyzer.explanation.ExplanationFacts.Amount;
import com.dealanalyzer.explanation.ExplanationFacts.Finding;
import com.dealanalyzer.explanation.ReportExplanation.Segment;
import com.dealanalyzer.explanation.ReportExplanation.Source;
import com.dealanalyzer.explanation.ReportExplanation.Statement;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The code-enforced gate every explanation passes, whichever producer wrote it. Each sentence cites
 * exactly one finding, and a sentence is shown only when each claim it makes is backed by that finding:
 * every subject it uses must be one of the finding's subjects, every state term must be permitted by
 * the finding (or be the precision of an amount it mentions), every amount it mentions must belong to
 * the finding's subjects, a finding recorded as unknown must be stated as unknown, an estimate may not be called exact, and amounts appear only as placeholders the
 * product renders from the report. Free text carrying a figure, a prohibited framing, links or markup
 * fails. Any failing sentence blocks the whole candidate: nothing is shown with a caveat.
 */
public final class ExplanationCheck {
  /** One sentence from either producer: text with amount placeholders, and the finding it cites. */
  public record Sentence(String text, List<String> findingIds) {
    public Sentence { findingIds = List.copyOf(findingIds); }
  }

  /** A candidate that cannot be shown. The reason is a code, never candidate text. */
  public static final class Rejected extends RuntimeException {
    private final String reason;
    public Rejected(String reason) { super("Explanation rejected: " + reason); this.reason = reason; }
    public String reason() { return reason; }
  }

  static final int MAX_SENTENCES = 6;
  static final int MAX_TEXT = 400;

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{(amount-[0-9]{1,3})\\}");

  private static final Pattern FIGURES = Pattern.compile("[0-9%$€£¥]|(?i)\\bpercent\\b");

  /** Spelled-out amounts would bypass the figure check. */
  private static final Pattern NUMBER_WORDS = Pattern.compile("(?i)\\b(two|three|four|five|six|seven|eight|nine|ten|"
      + "eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|"
      + "sixty|seventy|eighty|ninety|hundreds?|thousands?|grand|millions?|billions?|dollars?|bucks|cents?|k|usd)\\b");

  /**
   * Framings an explanation may never use: describing the product as an automated negotiator, purchase
   * safety or signing, legal, tax, mechanical, title or vehicle-history conclusions, savings or their
   * causation, guarantees, and advice.
   */
  static final Pattern PROHIBITED = Pattern.compile("(?i)(?<![\\w-])("
      + "ai|a\\.i\\.|artificial intelligence|negotiator|negotiators|robot|bot|chatbot|"
      + "safe|safely|safety|unsafe|sign|signs|signed|signing|signature|"
      + "legal|legally|illegal|law|laws|lawful|unlawful|compliant|compliance|regulat\\w*|attorneys?|lawyers?|contracts?|"
      + "tax|taxes|taxed|taxable|deductible|irs|"
      + "mechanic\\w*|condition|reliab\\w*|inspect\\w*|repairs?|"
      + "title|titled|liens?|history|accidents?|damaged?|flood|salvage|recalls?|carfax|autocheck|odometer|"
      + "save|saves|saving|savings|cheaper|guarantee\\w*|warrant\\w*|risk-free|"
      + "because of (this|the) (report|product|analysis)|thanks to|"
      + "fraud\\w*|scams?|advice|advise|recommend\\w*|you should|you must)(?![\\w-])");

  private static final Pattern MARKUP = Pattern.compile("(?i)[<>{}\\[\\]\\\\*_#`|]|https?:|www\\.|@|\\p{Cntrl}");
  private static final Pattern SPANISH_FRAMING = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}_-])(?:"
      + "inteligencia artificial|negociador(?:es)?|robot|bot|chatbot|"
      + "segur[oa]s?|seguridad|firmar|firma|firmado|firmando|"
      + "legal(?:es)?|legalmente|ilegal(?:es)?|ley(?:es)?|cumplimiento|normativa|reglamento|abogad[oa]s?|contratos?|"
      + "impuestos?|tributari[oa]s?|deducible|mecánic[oa]s?|condición|inspección|reparacion(?:es)?|reparación|"
      + "títulos?|gravamen|historial|historia|accidentes?|dañad[oa]s?|inundación|siniestro|retiros?|odómetro|"
      + "ahorrar|ahorros?|ahorra|ahorrará|garant[ií]as?|garantiz\\p{L}*|sin riesgo|"
      + "consejos?|asesoría|asesoramiento|recomend\\p{L}*|debería|debe usted|usted debe"
      + ")(?![\\p{L}\\p{N}_-])");

  private ExplanationCheck() {}

  public static List<Statement> check(ExplanationFacts facts, List<Sentence> sentences) {
    return check(facts, sentences, "en");
  }

  public static List<Statement> check(ExplanationFacts facts, List<Sentence> sentences, String language) {
    if (sentences.isEmpty()) throw new Rejected("NOTHING_TO_EXPLAIN");
    if (sentences.size() > MAX_SENTENCES) throw new Rejected("TOO_MANY_SENTENCES");
    List<Statement> statements = new ArrayList<>();
    for (Sentence sentence : sentences) statements.add(statement(facts, sentence, language));
    return statements;
  }

  private static Statement statement(ExplanationFacts facts, Sentence sentence, String language) {
    String text = sentence.text() == null ? "" : sentence.text().strip();
    if (text.isEmpty() || text.length() > MAX_TEXT) throw new Rejected("TEXT_OUT_OF_BOUNDS");
    // One finding per sentence, so no state can be borrowed from one finding for another's subject.
    if (sentence.findingIds().size() != 1) throw new Rejected("NOT_ONE_FINDING");
    Finding finding = facts.finding(sentence.findingIds().getFirst());
    if (finding == null) throw new Rejected("UNKNOWN_FINDING");
    List<Segment> segments = new ArrayList<>();
    List<Amount> referenced = new ArrayList<>();
    StringBuilder prose = new StringBuilder();
    Matcher matcher = PLACEHOLDER.matcher(text);
    int last = 0;
    while (matcher.find()) {
      Amount amount = facts.amount(matcher.group(1));
      if (amount == null) throw new Rejected("UNKNOWN_AMOUNT");
      if (amount.subjects().stream().noneMatch(finding.amountSubjects()::contains)) throw new Rejected("UNRELATED_AMOUNT");
      if (matcher.start() > last) segments.add(Segment.text(text.substring(last, matcher.start())));
      if ("es".equals(language)) {
        var format = java.text.NumberFormat.getCurrencyInstance(java.util.Locale.forLanguageTag("es-US"));
        format.setCurrency(java.util.Currency.getInstance(amount.currency()));
        String rendered = !amount.known() ? "desconocido" : format.format(java.math.BigDecimal.valueOf(amount.minorUnits(), 2))
            + ("ESTIMATED".equals(amount.precision()) ? " (estimado)" : "");
        segments.add(new Segment(rendered, amount.id(), amount.index(), amount.precision()));
      } else segments.add(Segment.amount(amount));
      referenced.add(amount);
      // The placeholder reads as an amount; keep the words around it apart for the scans below.
      prose.append(text, last, matcher.start()).append(' ');
      last = matcher.end();
    }
    if (last < text.length()) segments.add(Segment.text(text.substring(last)));
    prose.append(text.substring(last));
    String words = prose.toString();

    if (FIGURES.matcher(words).find() || NUMBER_WORDS.matcher(words).find()) throw new Rejected("AMOUNT_RESTATED");
    if (MARKUP.matcher(words).find()) throw new Rejected("MARKUP");
    if (PROHIBITED.matcher(words).find()) throw new Rejected("PROHIBITED_FRAMING");
    if ("es".equals(language)) {
      if (SPANISH_FRAMING.matcher(words).find() || com.dealanalyzer.ai.ProhibitedClaims.contains(words, "es"))
        throw new Rejected("PROHIBITED_FRAMING");
      if (Pattern.compile("(?i)\\b(the|this|these|those|unavailable|unknown|requires|matches|above|below|within)\\b")
          .matcher(words).find()) throw new Rejected("LANGUAGE_MISMATCH");
      if (Pattern.compile("(?iu)(?<![\\p{L}])(?:cero|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez|once|doce|trece|catorce|quince|dieciséis|diecisiete|dieciocho|diecinueve|veint\\p{L}*|treinta|cuarenta|cincuenta|sesenta|setenta|ochenta|noventa|cien|ciento|doscientos|trescientos|cuatrocientos|quinientos|seiscientos|setecientos|ochocientos|novecientos|mil|miles|millón|millones|d[oó]lar(?:es)?|centavos?|por ciento)(?![\\p{L}])")
          .matcher(words).find()) throw new Rejected("AMOUNT_RESTATED");
    }

    Set<Term> permitted = EnumSet.noneOf(Term.class);
    permitted.addAll(finding.permits());
    referenced.forEach(amount -> permitted.add(amount.precisionTerm()));
    Set<Subject> covered = finding.subjects();
    List<Term> terms = ClaimVocabulary.terms(words);
    if (terms.contains(Term.EXACT) && referenced.stream().anyMatch(amount -> !"EXACT".equals(amount.precision())))
      throw new Rejected("ESTIMATE_STATED_AS_EXACT");
    for (Term term : terms) if (!permitted.contains(term)) throw new Rejected("UNSUPPORTED_CLAIM");
    for (Subject subject : ClaimVocabulary.subjects(words))
      if (!covered.contains(subject)) throw new Rejected("UNSUPPORTED_SUBJECT");
    if (!finding.requires().isEmpty() && terms.stream().noneMatch(finding.requires()::contains))
      throw new Rejected("UNKNOWN_NOT_STATED");

    List<Source> sources = new ArrayList<>();
    sources.add(new Source(finding.id(), finding.sourcePointer(), finding.sourceValue()));
    referenced.stream().distinct().forEach(amount -> sources.add(new Source(amount.id(), amount.sourcePointer(),
        amount.minorUnits() == null ? null : Long.toString(amount.minorUnits()))));
    return new Statement(segments, sources);
  }
}

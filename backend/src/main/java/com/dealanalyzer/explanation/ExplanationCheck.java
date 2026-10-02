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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The code-enforced gate every explanation passes, whichever producer wrote it. A sentence is shown
 * only when each claim it makes is backed by a finding it cites: every state term and subject it uses
 * must be permitted by a cited finding or referenced amount, a finding recorded as unknown must be
 * stated as unknown, an estimate may not be called exact, and amounts appear only as placeholders the
 * product renders from the report. Free text carrying a figure, a prohibited framing, links or markup
 * fails. Any failing sentence blocks the whole candidate: nothing is shown with a caveat.
 */
public final class ExplanationCheck {
  /** One sentence from either producer: text with amount placeholders, and the findings it cites. */
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
  static final int MAX_FINDINGS = 3;
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

  private ExplanationCheck() {}

  public static List<Statement> check(ExplanationFacts facts, List<Sentence> sentences) {
    if (sentences.isEmpty()) throw new Rejected("NOTHING_TO_EXPLAIN");
    if (sentences.size() > MAX_SENTENCES) throw new Rejected("TOO_MANY_SENTENCES");
    List<Statement> statements = new ArrayList<>();
    for (Sentence sentence : sentences) statements.add(statement(facts, sentence));
    return statements;
  }

  private static Statement statement(ExplanationFacts facts, Sentence sentence) {
    String text = sentence.text() == null ? "" : sentence.text().strip();
    if (text.isEmpty() || text.length() > MAX_TEXT) throw new Rejected("TEXT_OUT_OF_BOUNDS");
    if (sentence.findingIds().isEmpty() || sentence.findingIds().size() > MAX_FINDINGS)
      throw new Rejected("FINDINGS_OUT_OF_BOUNDS");
    List<Finding> cited = new ArrayList<>();
    for (String id : new LinkedHashSet<>(sentence.findingIds())) {
      Finding finding = facts.finding(id);
      if (finding == null) throw new Rejected("UNKNOWN_FINDING");
      cited.add(finding);
    }
    List<Segment> segments = new ArrayList<>();
    List<Amount> referenced = new ArrayList<>();
    StringBuilder prose = new StringBuilder();
    Matcher matcher = PLACEHOLDER.matcher(text);
    int last = 0;
    while (matcher.find()) {
      Amount amount = facts.amount(matcher.group(1));
      if (amount == null) throw new Rejected("UNKNOWN_AMOUNT");
      if (matcher.start() > last) segments.add(Segment.text(text.substring(last, matcher.start())));
      segments.add(Segment.amount(amount));
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

    Set<Term> permitted = EnumSet.noneOf(Term.class);
    Set<Subject> covered = EnumSet.noneOf(Subject.class);
    cited.forEach(finding -> { permitted.addAll(finding.permits()); covered.addAll(finding.subjects()); });
    referenced.forEach(amount -> { permitted.add(amount.precisionTerm()); covered.addAll(amount.subjects()); });
    List<Term> terms = ClaimVocabulary.terms(words);
    if (terms.contains(Term.EXACT) && referenced.stream().anyMatch(amount -> !"EXACT".equals(amount.precision())))
      throw new Rejected("ESTIMATE_STATED_AS_EXACT");
    for (Term term : terms) if (!permitted.contains(term)) throw new Rejected("UNSUPPORTED_CLAIM");
    for (Subject subject : ClaimVocabulary.subjects(words))
      if (!covered.contains(subject)) throw new Rejected("UNSUPPORTED_SUBJECT");
    for (Finding finding : cited) {
      if (!finding.requires().isEmpty() && terms.stream().noneMatch(finding.requires()::contains))
        throw new Rejected("UNKNOWN_NOT_STATED");
    }

    List<Source> sources = new ArrayList<>();
    cited.forEach(finding -> sources.add(new Source(finding.id(), finding.sourcePointer(), finding.sourceValue())));
    referenced.stream().distinct().forEach(amount -> sources.add(new Source(amount.id(), amount.sourcePointer(),
        amount.minorUnits() == null ? null : Long.toString(amount.minorUnits()))));
    return new Statement(segments, sources);
  }
}

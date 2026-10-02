package com.dealanalyzer.explanation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The closed vocabulary the claim check reads: state terms (what a sentence asserts about a finding)
 * and subjects (what the sentence is about). Every term or subject found in a sentence must be backed
 * by a finding the sentence cites. Longer phrases win, so "not available" is read as unavailable and
 * "does not match" as a difference.
 */
final class ClaimVocabulary {
  enum Term {
    MATCH, DIFFERS, RECONCILES, REVIEW, UNVERIFIABLE, UNAVAILABLE, AVAILABLE, STALE,
    ABOVE, BELOW, WITHIN, STRONG, FAIR, POOR, EXACT, ESTIMATED
  }

  enum Subject { APPROVAL, RATING, MARKET, CASH, FINANCING, PAYMENT, ADD_ONS }

  private static final Map<String, Term> TERMS = new LinkedHashMap<>();
  private static final Map<String, Subject> SUBJECTS = new LinkedHashMap<>();

  static {
    terms(Term.MATCH, "match", "matches", "matched", "matching", "consistent", "agree", "agrees", "same");
    terms(Term.DIFFERS, "differ", "differs", "differed", "different", "difference", "differences", "mismatch",
        "mismatches", "mismatched", "does not match", "do not match", "did not match", "not match", "inconsistent",
        "discrepancy", "discrepancies", "does not reconcile", "do not reconcile", "changed", "changes");
    terms(Term.RECONCILES, "reconcile", "reconciles", "reconciled");
    terms(Term.REVIEW, "review", "reviews", "reviewed");
    terms(Term.UNVERIFIABLE, "cannot verify", "can not verify", "unable to verify", "could not verify",
        "cannot be verified", "could not be verified", "unverified", "unverifiable");
    terms(Term.UNAVAILABLE, "unavailable", "not available", "unknown", "missing", "not recorded", "absent",
        "insufficient", "incomplete");
    terms(Term.AVAILABLE, "available", "complete", "confirmed", "verified");
    terms(Term.STALE, "stale", "outdated", "out of date", "out-of-date", "expired");
    terms(Term.ABOVE, "above", "higher", "higher than", "more than", "exceeds", "exceed", "exceeded", "over",
        "beyond", "too high");
    terms(Term.BELOW, "below", "lower", "lower than", "less than", "under", "beneath");
    terms(Term.WITHIN, "within", "inside", "in range", "in line");
    terms(Term.STRONG, "strong", "good", "great", "excellent", "favorable", "favourable", "bargain", "attractive");
    terms(Term.FAIR, "fair", "reasonable", "acceptable");
    terms(Term.POOR, "poor", "bad", "overpriced", "unfavorable", "unfavourable", "expensive", "costly");
    terms(Term.EXACT, "exact", "exactly", "precise", "precisely", "certain", "certainly", "definitive",
        "definitively", "final", "firm");
    terms(Term.ESTIMATED, "estimate", "estimates", "estimated", "approximate", "approximately", "roughly");
    subjects(Subject.APPROVAL, "approved", "approval", "approve", "walk-away", "limit", "limits", "baseline");
    subjects(Subject.RATING, "rating", "rated");
    subjects(Subject.MARKET, "market", "comparable", "comparables", "listing", "listings");
    subjects(Subject.CASH, "cash");
    subjects(Subject.FINANCING, "financing", "financed", "finance", "loan", "loans", "lender", "apr", "interest",
        "schedule");
    subjects(Subject.PAYMENT, "payment", "payments", "monthly");
    subjects(Subject.ADD_ONS, "add-on", "add-ons", "addon", "addons");
  }

  // "verified" counts as AVAILABLE: a sentence that cites only unavailable findings cannot call
  // anything verified.
  private static final Pattern TERM_PATTERN = pattern(TERMS.keySet().stream().toList());
  private static final Pattern SUBJECT_PATTERN = pattern(SUBJECTS.keySet().stream().toList());

  private ClaimVocabulary() {}

  static List<Term> terms(String text) {
    return scan(TERM_PATTERN, text).stream().map(TERMS::get).toList();
  }

  static List<Subject> subjects(String text) {
    return scan(SUBJECT_PATTERN, text).stream().map(SUBJECTS::get).toList();
  }

  private static List<String> scan(Pattern pattern, String text) {
    List<String> found = new ArrayList<>();
    Matcher matcher = pattern.matcher(text.toLowerCase(Locale.ROOT));
    while (matcher.find()) found.add(matcher.group(1).replaceAll("\\s+", " "));
    return found;
  }

  private static Pattern pattern(List<String> phrases) {
    String alternation = phrases.stream().sorted(Comparator.comparingInt(String::length).reversed())
        .map(phrase -> Pattern.quote(phrase).replace(" ", "\\E\\s+\\Q"))
        .collect(Collectors.joining("|"));
    return Pattern.compile("(?<![\\w-])(" + alternation + ")(?![\\w-])");
  }

  private static void terms(Term term, String... phrases) {
    for (String phrase : phrases) TERMS.put(phrase, term);
  }

  private static void subjects(Subject subject, String... phrases) {
    for (String phrase : phrases) SUBJECTS.put(phrase, subject);
  }
}

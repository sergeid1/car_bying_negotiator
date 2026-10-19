package com.dealanalyzer.economics.fees;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Normalised keyword matching for the many ways paperwork names a fee (ticket 139). A label and each term are
 * reduced to lower-case word tokens: accents and apostrophes are dropped, "&" reads as "and", and a short dotted
 * or slashed abbreviation such as "T.T.&L.", "D.M.V." or "Lic." is closed up ("ttl", "dmv", "lic"). A term matches a run
 * of label tokens when every term token matches in order. One token matches another when it is the same word,
 * when one starts the other (so "regist" and "registrations" both read as "registration"), or when it is a
 * misspelling one edit away, two for words of eight letters or more ("registeration", "liscense", "titel").
 * Tokens of one or two letters must match exactly.
 */
final class FeeLabels {
  private FeeLabels() {}

  static List<String> tokens(String text) {
    String folded = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replace("’", "").replace("'", "");
    List<String> tokens = new ArrayList<>();
    for (String raw : folded.trim().split("\\s+")) {
      if (raw.isEmpty()) continue;
      String compact = raw.replaceAll("[^\\p{L}\\p{N}]", "");
      // "tt&l", "d.m.v.", "t/t/l" and "lic." are one abbreviation; "tag&title" and "tag/title" are two words.
      if (!compact.isEmpty() && compact.length() <= 5 && !compact.equals("and")) {
        tokens.add(compact);
        continue;
      }
      for (String part : raw.replace("&", " and ").split("[^\\p{L}\\p{N}]+"))
        if (!part.isEmpty()) tokens.add(part);
    }
    return tokens;
  }

  /** The indices of the label tokens any term matches; empty when none does. */
  static Set<Integer> match(List<String> label, List<String> terms) {
    Set<Integer> matched = new TreeSet<>();
    for (String term : terms) {
      var wanted = tokens(term);
      if (wanted.isEmpty()) continue;
      for (int at = 0; at + wanted.size() <= label.size(); at++) {
        boolean all = true;
        for (int i = 0; i < wanted.size() && all; i++) all = token(label.get(at + i), wanted.get(i));
        if (all) for (int i = 0; i < wanted.size(); i++) matched.add(at + i);
      }
    }
    return matched;
  }

  static boolean matches(String label, List<String> terms) {
    return !match(tokens(label), terms).isEmpty();
  }

  static boolean token(String label, String term) {
    if (label.isEmpty()) return false;
    if (label.length() < 3 || term.length() < 3) return label.equals(term);
    if (label.startsWith(term)) return true;
    // An abbreviation of the term: "insp", "regis", "batt".
    if (label.length() >= 4 && term.startsWith(label)) return true;
    if (term.length() < 5 || label.length() < 4) return false;
    int allowed = term.length() >= 8 ? 2 : 1;
    // Compare a longer label word on the term's length too, so "registerations" reads as "registration".
    return distance(label, term) <= allowed
        || label.length() > term.length() && distance(label.substring(0, term.length() + 1), term) <= allowed;
  }

  /** Optimal string alignment distance: insertions, deletions, substitutions and adjacent swaps. */
  static int distance(String a, String b) {
    int[][] d = new int[a.length() + 1][b.length() + 1];
    for (int i = 0; i <= a.length(); i++) d[i][0] = i;
    for (int j = 0; j <= b.length(); j++) d[0][j] = j;
    for (int i = 1; i <= a.length(); i++)
      for (int j = 1; j <= b.length(); j++) {
        int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
        if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1))
          d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
      }
    return d[a.length()][b.length()];
  }
}

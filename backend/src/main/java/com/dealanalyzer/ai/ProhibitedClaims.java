package com.dealanalyzer.ai;

import java.util.Map;
import java.util.regex.Pattern;

/** One code-owned list for shipped-copy checks and untrusted model answers. */
public final class ProhibitedClaims {
  private ProhibitedClaims() {}

  public static final Map<String, Pattern> PATTERNS = Map.of(
      "illegal", word("illegal|unlawful|violates the law"),
      "fraud", word("fraud|fraudulent|scam|deceptive"),
      "advice", word("advice|we advise|you should|we recommend that you"),
      "safe to sign", Pattern.compile("safe to sign", Pattern.CASE_INSENSITIVE),
      "guarantee", word("guarantee|guaranteed|guarantees|risk-free|best[\\s_-]*rate|cheapest"),
      "savings", Pattern.compile("(?i)\\b(you will save|savings of|save you \\$)"),
      "negotiates for you", word("(we|it) negotiates?|negotiates? (for|with dealers for)"
          + " (you|the buyer)|negotiating for (you|the buyer)"),
      "on your behalf", word("on (your|the buyer['’]s|a buyer['’]s|buyers['’]) behalf"),
      "agent", word("our agent|your agent|ai agent|ai negotiator|autonomous|autonomously"
          + "|(your|the buyer['’]s) representative|represents? (you|the buyer)"),
      "contacts dealers", word("(we|deal x-ray|the platform|the product) (will )?(contacts?|emails?|writes? to)"
          + " (the |a |your )?dealers?|(contacts?|emails?|writes? to) (the |a |your )?dealers?"
          + " (for (you|the buyer)|itself|in its own name)"));

  public static boolean contains(String text) {
    String normalized = text.replaceAll("\\s+", " ").strip();
    return PATTERNS.values().stream().anyMatch(pattern -> pattern.matcher(normalized).find());
  }

  private static Pattern word(String alternatives) {
    return Pattern.compile("(?i)\\b(" + alternatives + ")\\b");
  }
}

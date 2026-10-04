package com.dealanalyzer.relay;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Finds the Buyer's email, phone or surname in a rendered outbound message (AGT-03). Matching is
 * deliberately broad: a false refusal costs a rewrite, a miss exposes the Buyer.
 */
final class BuyerIdentityScreen {
  private static final Pattern PHONE_LIKE = Pattern.compile("\\+?\\d[\\d\\s().\\-]{5,}\\d");

  record Identity(Collection<String> emails, Collection<String> phones, Collection<String> surnames) {
    @Override public String toString() { return "Identity[redacted]"; }
  }

  enum Match { EMAIL, PHONE, SURNAME }

  /** The kinds of Buyer data found in the text; empty when it is clean. Never returns the matched values. */
  static List<Match> find(String text, Identity identity) {
    String lower = text.toLowerCase(Locale.ROOT);
    var found = new java.util.ArrayList<Match>();
    if (identity.emails().stream().filter(BuyerIdentityScreen::present)
        .anyMatch(email -> lower.contains(email.trim().toLowerCase(Locale.ROOT)))) found.add(Match.EMAIL);
    if (containsPhone(text, identity.phones())) found.add(Match.PHONE);
    if (identity.surnames().stream().filter(BuyerIdentityScreen::present).map(String::trim)
        .filter(name -> name.length() >= 2)
        .anyMatch(name -> Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(name.toLowerCase(Locale.ROOT))
            + "(?![\\p{L}\\p{N}])").matcher(lower).find())) found.add(Match.SURNAME);
    return List.copyOf(found);
  }

  private static boolean containsPhone(String text, Collection<String> phones) {
    var wanted = phones.stream().filter(BuyerIdentityScreen::present).map(BuyerIdentityScreen::significantDigits)
        .filter(digits -> digits.length() >= 7).toList();
    if (wanted.isEmpty()) return false;
    var matcher = PHONE_LIKE.matcher(text);
    while (matcher.find()) {
      String digits = significantDigits(matcher.group());
      for (String phone : wanted) if (digits.contains(phone)) return true;
    }
    return false;
  }

  /** The last ten digits, which drops a country code written either way. */
  private static String significantDigits(String value) {
    String digits = value.replaceAll("\\D", "");
    return digits.length() > 10 ? digits.substring(digits.length() - 10) : digits;
  }

  private static boolean present(String value) { return value != null && !value.isBlank(); }

  private BuyerIdentityScreen() {}
}

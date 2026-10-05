package com.dealanalyzer.quote;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;

/** Verified money and actual sent counteroffers only; no dealer prose or extraction candidates. */
final class DealerDeclinedRule {
  record Offer(String id, int version, Long sellingPriceMinor, String currency, Instant verifiedAt) {}
  record Sent(String id, String offerId, Instant sentAt) {}

  static boolean holds(List<Offer> offers, List<Sent> sent, int rounds) {
    if (sent.size() < rounds || offers.isEmpty()) return false;
    int resetVersion = 0;
    for (int i = 1; i < offers.size(); i++)
      if (lower(offers.get(i), offers.get(i - 1))) resetVersion = offers.get(i).version();
    var responses = new HashSet<String>();
    for (Sent counter : sent.subList(sent.size() - rounds, sent.size())) {
      Offer before = offers.stream().filter(offer -> offer.id().equals(counter.offerId())).findFirst().orElse(null);
      if (before == null || before.version() < resetVersion || before.sellingPriceMinor() == null) return false;
      Offer after = offers.stream().filter(offer -> offer.version() > before.version()
          && offer.verifiedAt().isAfter(counter.sentAt())).findFirst().orElse(null);
      if (after == null || after.sellingPriceMinor() == null || !sameCurrency(before, after)
          || lower(after, before) || !responses.add(after.id())) return false;
    }
    return true;
  }

  static boolean lower(Offer after, Offer before) {
    return after.sellingPriceMinor() != null && before.sellingPriceMinor() != null
        && sameCurrency(before, after) && after.sellingPriceMinor() < before.sellingPriceMinor();
  }

  private static boolean sameCurrency(Offer first, Offer second) {
    return first.currency() != null && first.currency().equals(second.currency());
  }
}

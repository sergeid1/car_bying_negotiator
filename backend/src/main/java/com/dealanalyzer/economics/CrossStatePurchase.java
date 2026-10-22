package com.dealanalyzer.economics;

import com.dealanalyzer.buyingcase.location.PostalJurisdictionResolver;
import com.dealanalyzer.buyingcase.location.PostalResolution;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * A car registered in one state and bought from a dealer in another (ticket 141). Tax is not calculated for such a
 * purchase: the dealer's tax line is used as written, and every other check runs as usual.
 */
@Component
public class CrossStatePurchase {
  /** Shown wherever a tax figure would otherwise be calculated; FindingText carries its Spanish. */
  public static final String TAX_NOT_CHECKED = "Tax is not checked because the dealer is in a different state from "
      + "the one where the car will be registered. The tax shown is the dealer's figure as written; confirm it with "
      + "your state's tax office or DMV before you sign.";

  private final JdbcTemplate jdbc;
  private final PostalJurisdictionResolver postal;

  public CrossStatePurchase(JdbcTemplate jdbc, PostalJurisdictionResolver postal) {
    this.jdbc = jdbc;
    this.postal = postal;
  }

  /** The dealer's state as "US-XX" when it is known and differs from the registration state; null otherwise. */
  public String dealerState(String caseId) {
    var row = jdbc.query("select buyer_jurisdiction, dealer_location from analysis_cases where id = ?",
        (r, n) -> new String[] {r.getString(1), r.getString(2)}, caseId).stream().findFirst().orElse(null);
    if (row == null) return null;
    String dealer = stateOf(row[1], postal);
    return differs(row[0], dealer) ? dealer : null;
  }

  /** "Raleigh, NC" or a dealer ZIP code from an older client names a state as "US-NC"; anything else names none. */
  public static String stateOf(String dealerLocation, PostalJurisdictionResolver postal) {
    if (dealerLocation == null) return null;
    if (dealerLocation.matches("[0-9]{5}")) {
      var resolved = postal.resolve(dealerLocation);
      return resolved.status() == PostalResolution.Status.RESOLVED ? resolved.jurisdiction() : null;
    }
    return dealerLocation.matches(".+, [A-Z]{2}") ? "US-" + dealerLocation.substring(dealerLocation.length() - 2) : null;
  }

  /** "US-FL:Miami-Dade" and "US-FL" are one state; an unknown side is never a difference. */
  static boolean differs(String jurisdiction, String dealerState) {
    return jurisdiction != null && dealerState != null && jurisdiction.matches("US-[A-Z]{2}(:.*)?")
        && !jurisdiction.substring(0, 5).equals(dealerState);
  }
}

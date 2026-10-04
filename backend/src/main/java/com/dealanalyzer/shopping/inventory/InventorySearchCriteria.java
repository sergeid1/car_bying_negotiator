package com.dealanalyzer.shopping.inventory;

import com.dealanalyzer.journey.ShoppingBrief;
import java.util.List;

/** Only Shopping Brief fields cross the search boundary; no account or contact details. */
public record InventorySearchCriteria(int briefVersion, String referenceListVersion, int yearFrom, int yearTo,
    ShoppingBrief.Choice make, ShoppingBrief.Choice model, List<ShoppingBrief.Choice> trims, int maxMileage,
    long priceCeilingMinor, String currency, String postalCode, String jurisdiction,
    List<ShoppingBrief.Choice> mustHaveFeatures, List<ShoppingBrief.Choice> niceToHaveFeatures) {
  public InventorySearchCriteria {
    trims = List.copyOf(trims); mustHaveFeatures = List.copyOf(mustHaveFeatures);
    niceToHaveFeatures = List.copyOf(niceToHaveFeatures);
  }

  public static InventorySearchCriteria from(ShoppingBrief brief) {
    return new InventorySearchCriteria(brief.version(), brief.referenceListVersion(), brief.yearFrom(), brief.yearTo(),
        brief.make(), brief.model(), brief.trims(), brief.maxMileage(), brief.priceCeilingMinor(), brief.currency(),
        brief.postalCode(), brief.jurisdiction(), brief.mustHaveFeatures(), brief.niceToHaveFeatures());
  }
}

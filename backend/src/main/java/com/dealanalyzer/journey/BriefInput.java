package com.dealanalyzer.journey;

import java.util.List;

/**
 * Shopping Brief fields as the Buyer submitted them. Every field is nullable here so that a missing
 * value is reported against its field by {@link ShoppingBriefValidator}, not as an unreadable body.
 * Make, model, trim and feature values are reference-list identifiers; free text is not accepted.
 */
public record BriefInput(Integer yearFrom, Integer yearTo, String makeId, String modelId, List<String> trimIds,
    Integer maxMileage, Integer priceCeiling, String postalCode, String jurisdiction, Integer radiusMiles,
    List<String> mustHaveFeatures, List<String> niceToHaveFeatures) {}

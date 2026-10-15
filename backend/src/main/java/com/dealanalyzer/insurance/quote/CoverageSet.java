package com.dealanalyzer.insurance.quote;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/** A whole policy's coverage: at most one line per type, held in type order so equal sets compare equal. */
public record CoverageSet(List<Coverage> coverages) {
  public CoverageSet {
    if (coverages == null || coverages.isEmpty() || coverages.stream().anyMatch(java.util.Objects::isNull))
      throw new IllegalArgumentException("At least one coverage is required");
    var types = new HashSet<Coverage.Type>();
    for (var coverage : coverages)
      if (!types.add(coverage.type())) throw new IllegalArgumentException("Each coverage type appears once");
    coverages = coverages.stream().sorted(Comparator.comparing(Coverage::type)).toList();
  }

  public static CoverageSet of(Coverage... coverages) { return new CoverageSet(List.of(coverages)); }

  public Optional<Coverage> find(Coverage.Type type) {
    return coverages.stream().filter(coverage -> coverage.type() == type).findFirst();
  }
}

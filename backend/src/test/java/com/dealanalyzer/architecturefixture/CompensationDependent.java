package com.dealanalyzer.architecturefixture;

import com.dealanalyzer.compensation.ProviderFees;

/** Deliberate forbidden dependency, loaded only as bytecode by the architecture negative test. */
public final class CompensationDependent {
  public ProviderFees fees() { return ProviderFees.CURRENT; }
}

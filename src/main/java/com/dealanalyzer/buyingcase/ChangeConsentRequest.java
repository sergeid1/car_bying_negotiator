package com.dealanalyzer.buyingcase;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ChangeConsentRequest(
    @NotNull ConsentPurpose purpose,
    boolean granted,
    @NotBlank String policyVersion) {

  public enum ConsentPurpose {
    SERVICE_PROCESSING,
    HUMAN_REVIEW,
    ANONYMIZED_RESEARCH
  }
}

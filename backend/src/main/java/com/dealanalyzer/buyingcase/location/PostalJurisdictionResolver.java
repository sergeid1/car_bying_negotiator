package com.dealanalyzer.buyingcase.location;

import com.dealanalyzer.buyingcase.location.PostalResolution.Status;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves a United States postal code to the jurisdiction that contains it. Support for that
 * jurisdiction is a separate question, answered by the configured economics policy packs, so
 * adding a state is a policy-pack change rather than a change here.
 */
@Service
public class PostalJurisdictionResolver {
  public static final Pattern POSTAL_CODE = Pattern.compile("\\d{5}");

  private final PostalJurisdictionRepository reference;

  PostalJurisdictionResolver(PostalJurisdictionRepository reference) {
    this.reference = reference;
  }

  /** Expects an already well-formed five-digit code; a malformed one is a field-level failure. */
  @Transactional(readOnly = true)
  public PostalResolution resolve(String postalCode) {
    if (postalCode == null || !POSTAL_CODE.matcher(postalCode).matches()) {
      throw new IllegalArgumentException("A postal code must be validated before resolution");
    }
    List<String> containing = reference.findByPostalCodeOrderByDominantDesc(postalCode).stream()
        .map(entity -> entity.jurisdiction)
        .toList();
    if (containing.isEmpty()) {
      return new PostalResolution(Status.UNRECOGNIZED, List.of());
    }
    if (containing.size() > 1) {
      // The dominant jurisdiction by land area is not evidence of where this Buyer registers a car.
      return new PostalResolution(Status.CROSSES_STATE_LINE, containing);
    }
    return new PostalResolution(Status.RESOLVED, containing);
  }
}

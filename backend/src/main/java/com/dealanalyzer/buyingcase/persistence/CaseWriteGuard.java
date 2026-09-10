package com.dealanalyzer.buyingcase.persistence;

import com.dealanalyzer.buyingcase.ResourceNotFoundException;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The lock must remain held by the caller's transaction through the external write. */
@Component
public class CaseWriteGuard {
  private final AnalysisCaseRepository cases;
  CaseWriteGuard(AnalysisCaseRepository cases) { this.cases = cases; }
  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
  public void lockActive(String id) {
    var c = cases.findLockedById(id).orElseThrow(() -> new ResourceNotFoundException("Buying Case not found"));
    if (!"ACTIVE".equals(c.lifecycle) || c.accessExpiresAt == null || !c.accessExpiresAt.isAfter(Instant.now()))
      throw new ResourceNotFoundException("Buying Case not found");
  }
}

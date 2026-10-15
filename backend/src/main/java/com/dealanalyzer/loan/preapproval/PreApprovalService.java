package com.dealanalyzer.loan.preapproval;

import com.dealanalyzer.operations.ProviderMeter;
import java.time.Instant;
import org.springframework.stereotype.Service;

/** Caller authorizes the case or account/journey and loads the recorded written authorization. */
@Service
public class PreApprovalService {
  private final PreApprovalAdapter adapter;
  private final ProviderMeter meter;
  public PreApprovalService(PreApprovalAdapter adapter, ProviderMeter meter) { this.adapter = adapter; this.meter = meter; }
  public PreApprovalAdapter.Result request(String caseId, String accountId, String journeyId,
      LoanApplication application, CreditAuthorization authorization) {
    CreditAuthorization.require(authorization, Instant.now());
    if (application == null) throw new IllegalArgumentException("Loan application required");
    return meter.callPreApproval(caseId, accountId, journeyId, adapter.provider(), application,
        () -> adapter.request(application, authorization));
  }
}

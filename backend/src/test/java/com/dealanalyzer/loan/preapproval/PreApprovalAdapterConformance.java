package com.dealanalyzer.loan.preapproval;

import static org.assertj.core.api.Assertions.*;
import static com.dealanalyzer.loan.preapproval.PreApprovalAdapter.*;
import com.dealanalyzer.partner.PartnerOutcome;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Replacement partners must run these scenarios using recorded fixtures, never live inquiries. */
public abstract class PreApprovalAdapterConformance {
  public enum Fixture { NORMAL, ZERO_APR, PAYMENT_MISMATCH, MIXED_OUTCOMES, HARD_INQUIRY, EXPIRED, UNAVAILABLE, TIMED_OUT, RATE_LIMITED }
  protected abstract PreApprovalAdapter adapter(Fixture fixture);
  protected abstract Instant now();
  protected LoanApplication application() {
    return new LoanApplication(2500000, 60, new LoanApplication.Vehicle(2024, "Honda", "Civic", 15000),
        8000000, LoanApplication.Housing.RENT, 150000);
  }
  protected CreditAuthorization authorization() { return new CreditAuthorization("fcra-test.v1", now().minusSeconds(60)); }
  private Result result(Fixture fixture) {
    var adapter = adapter(fixture);
    var result = adapter.request(application(), authorization());
    assertThat(PreApprovalContract.failure(result, adapter.provider(), application(), now())).isNull();
    assertThat(result.provider().licenceMetadata()).isNotBlank();
    assertThat(result.retrievedAt()).isNotNull();
    return result;
  }
  @Test void recordedAuthorizationIsRequiredBeforeSending() {
    assertThatIllegalArgumentException().isThrownBy(() -> adapter(Fixture.NORMAL).request(application(), null));
    assertThatIllegalArgumentException().isThrownBy(() -> adapter(Fixture.NORMAL).request(application(),
        new CreditAuthorization("fcra-test.v1", now().plusSeconds(1))));
  }
  @Test void normalOfferPreservesTermsConditionsExpiryAndSource() {
    var result = result(Fixture.NORMAL);
    assertThat(result.availability()).isEqualTo(Availability.AVAILABLE);
    var offer = result.lenders().getFirst().preApproval();
    assertThat(offer.amountMinor()).isEqualTo(application().amountMinor());
    assertThat(offer.currency()).isEqualTo("USD");
    assertThat(offer.inquiryType()).isEqualTo(InquiryType.SOFT);
    assertThat(offer.conditions()).isNotNull();
    assertThat(offer.expiresAt()).isAfter(now());
    assertThat(offer.source().reference()).isNotBlank();
    assertThat(offer.continueUrl().getScheme()).isEqualTo("https");
  }
  @Test void lenderOutcomesRemainExplicitAndOnlyOfferedCarriesTerms() {
    var entries = result(Fixture.MIXED_OUTCOMES).lenders();
    assertThat(entries).extracting(LenderEntry::outcome).containsExactlyInAnyOrder(PartnerOutcome.values());
    for (var entry : entries) assertThat(entry.preApproval() != null).isEqualTo(entry.outcome() == PartnerOutcome.OFFERED);
  }
  @Test void zeroAprAndPaymentMismatchUsePlatformCalculation() {
    var zero = PreApprovalContract.orderedOffers(result(Fixture.ZERO_APR), application(), now()).getFirst();
    assertThat(zero.totalInterestMinor()).isZero();
    assertThat(zero.totalCostMinor()).isEqualTo(application().amountMinor());
    var mismatch = PreApprovalContract.orderedOffers(result(Fixture.PAYMENT_MISMATCH), application(), now()).getFirst();
    assertThat(mismatch.reconciliation().withinTolerance()).isFalse();
    assertThat(mismatch.reconciliation().toleranceMinor()).isEqualTo(100);
    assertThat(mismatch.lenderMinusCalculatedMinor()).isEqualTo(mismatch.preApproval().lenderPaymentMinor() - mismatch.paymentMinor());
  }
  @Test void unsafeExpiredOrUnavailableResultsNeverSupplyOffers() {
    for (var fixture : new Fixture[] {Fixture.HARD_INQUIRY, Fixture.EXPIRED, Fixture.UNAVAILABLE, Fixture.TIMED_OUT, Fixture.RATE_LIMITED}) {
      var result = result(fixture);
      assertThat(result.lenders()).isEmpty();
      assertThat(result.notice()).isNotBlank();
      assertThat(result.reason()).isEqualTo(switch (fixture) {
        case HARD_INQUIRY -> Reason.NON_SOFT_INQUIRY;
        case EXPIRED -> Reason.EXPIRED;
        case TIMED_OUT -> Reason.TIMED_OUT;
        case RATE_LIMITED -> Reason.RATE_LIMITED;
        default -> Reason.PROVIDER_UNAVAILABLE;
      });
    }
  }
}

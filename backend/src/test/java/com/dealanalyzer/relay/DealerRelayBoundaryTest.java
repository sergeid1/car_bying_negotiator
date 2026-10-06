package com.dealanalyzer.relay;

import static org.assertj.core.api.Assertions.*;

import com.dealanalyzer.relay.BuyerIdentityScreen.Identity;
import com.dealanalyzer.relay.BuyerIdentityScreen.Match;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class DealerRelayBoundaryTest {
  private static final Identity BUYER = new Identity(List.of("Avery.Private@Example.com"), List.of("+1 (919) 555-0142"),
      List.of("O'Neil-Quintero"));

  @Test void findsTheBuyersEmailPhoneAndSurnameInAnyCaseOrFormat() {
    assertThat(BuyerIdentityScreen.find("reach avery.private@example.COM", BUYER)).containsExactly(Match.EMAIL);
    for (String phone : List.of("919-555-0142", "(919) 555 0142", "+1.919.555.0142", "19195550142", "tel 9195550142"))
      assertThat(BuyerIdentityScreen.find("Call " + phone + " today", BUYER)).as(phone).containsExactly(Match.PHONE);
    assertThat(BuyerIdentityScreen.find("Thanks, the o'neil-quintero family", BUYER)).containsExactly(Match.SURNAME);
    assertThat(BuyerIdentityScreen.find("Dear O'NEIL-QUINTERO,", BUYER)).containsExactly(Match.SURNAME);
  }

  @Test void ordinaryDealerTalkIsNotMistakenForTheBuyer() {
    assertThat(BuyerIdentityScreen.find("Is the 2021 trim at $24,919 still available? Stock 5550142A.", BUYER)).isEmpty();
    assertThat(BuyerIdentityScreen.find("The O'Neil-Quinteros dealership", BUYER)).isEmpty();
    assertThat(BuyerIdentityScreen.find("anything", new Identity(List.of(""), List.of("12"), List.of("Q")))).isEmpty();
  }

  @Test void theFakeRelayCannotServeAPilotOrProduction() {
    var fake = new FakeDealerMailRelay();
    for (String deployment : List.of("pilot", "production")) {
      var environment = new MockEnvironment().withProperty("evidence.deployment", deployment);
      assertThatThrownBy(() -> new RelayProductionGuard(environment, fake).afterSingletonsInstantiated())
          .isInstanceOf(IllegalStateException.class);
    }
    var pilotProfile = new MockEnvironment(); pilotProfile.setActiveProfiles("pilot");
    assertThatThrownBy(() -> new RelayProductionGuard(pilotProfile, fake).afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class);
    new RelayProductionGuard(pilotProfile, new UnavailableDealerMailRelay()).afterSingletonsInstantiated();
    new RelayProductionGuard(new MockEnvironment(), fake).afterSingletonsInstantiated();
  }

  /** Ticket 84: no code path can send email to a dealer from a platform address. */
  @Test void noOutboundRelayCallRemains() {
    assertThat(java.util.Arrays.stream(DealerMailRelay.class.getDeclaredMethods()).map(java.lang.reflect.Method::getName))
        .containsExactlyInAnyOrder("domain", "receivesMail");
    assertThat(java.util.Arrays.stream(DealerMailRelay.class.getDeclaredClasses()).map(Class::getSimpleName))
        .containsExactlyInAnyOrder("InboundMail", "Attachment");
    var forbidden = java.util.Set.of("send", "retry", "checkDelivery", "recordDelivery", "optOutByLink", "lookup");
    for (Class<?> type : List.of(RelayService.class, DealerMessageHandoff.class, FakeDealerMailRelay.class, UnavailableDealerMailRelay.class))
      assertThat(java.util.Arrays.stream(type.getDeclaredMethods()).map(java.lang.reflect.Method::getName))
          .as(type.getSimpleName()).doesNotContainAnyElementsOf(forbidden);
    assertThat(java.util.Arrays.stream(RelayService.class.getDeclaredFields()).map(java.lang.reflect.Field::getName))
        .doesNotContain("FOOTER");
    assertThatThrownBy(() -> Class.forName("com.dealanalyzer.relay.DealerOptOutController")).isInstanceOf(ClassNotFoundException.class);
    assertThat(java.util.Arrays.stream(RelayController.class.getDeclaredMethods()).map(java.lang.reflect.Method::getName))
        .doesNotContain("retry", "checkDelivery");
  }

  @Test void mailRecordsNeverPrintTheirContent() {
    assertThat(new DealerMailRelay.InboundMail("p", "t", "f", "secret", "secret", null).toString()).doesNotContain("secret");
  }
}

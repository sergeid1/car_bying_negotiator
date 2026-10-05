package com.dealanalyzer.account;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletResponse;

class AccountSessionCookieTest {
  @Test void developmentCanExplicitlyUseHttpWithTheOtherCookieProtections() {
    var cookie = new AccountSessionCookie(false, new MockEnvironment());
    var response = new MockHttpServletResponse();
    cookie.issue(response, new BuyerAccountService.Session("secret", Instant.now().plusSeconds(60)));
    assertThat(response.getHeader("Set-Cookie")).contains("account_session=secret", "HttpOnly",
        "SameSite=Strict", "Path=/api/v1", "Max-Age=60").doesNotContain("Secure");
    var cleared = new MockHttpServletResponse();
    cookie.clear(cleared);
    assertThat(cleared.getHeader("Set-Cookie")).contains("Max-Age=0", "HttpOnly", "SameSite=Strict", "Path=/api/v1");
  }

  @Test void pilotAndProductionCannotDisableSecureCookies() {
    for (String profile : new String[] {"pilot", "prod", "production"}) {
      var environment = new MockEnvironment();
      environment.setActiveProfiles(profile);
      assertThatThrownBy(() -> new AccountSessionCookie(false, environment))
          .isInstanceOf(IllegalStateException.class).hasMessageContaining("must be secure");
      assertThatCode(() -> new AccountSessionCookie(true, environment)).doesNotThrowAnyException();
      var deployment = new MockEnvironment().withProperty("evidence.deployment", profile);
      assertThatThrownBy(() -> new AccountSessionCookie(false, deployment)).isInstanceOf(IllegalStateException.class);
    }
  }
}

package com.dealanalyzer.api;

import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

final class AccountSessionTestSupport {
  private AccountSessionTestSupport() {}

  static String verify(RestClient client, String secret) {
    var response = client.post().uri("/api/v1/account/sign-in/verify")
        .contentType(MediaType.APPLICATION_JSON).body(Map.of("token", secret))
        .retrieve().toEntity(String.class);
    assertThat(response.getBody()).doesNotContain("sessionToken");
    String setCookie = response.getHeaders().getFirst("Set-Cookie");
    assertThat(setCookie).startsWith("account_session=");
    return setCookie.substring("account_session=".length(), setCookie.indexOf(';'));
  }

  static String cookie(String token) {
    return "account_session=" + token;
  }
}

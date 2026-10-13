package com.dealanalyzer.account;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SocialSignInConfigurationTest {
  private static final String PREFIX = "account.social-sign-in.";

  private static MockEnvironment production() {
    return new MockEnvironment().withProperty("evidence.deployment", "pilot");
  }

  private static String pem() throws Exception {
    var generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    return "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
        + "\n-----END PRIVATE KEY-----";
  }

  private static MockEnvironment apple(MockEnvironment environment) throws Exception {
    return environment.withProperty(PREFIX + "apple.client-id", "com.example.signin").withProperty(PREFIX + "apple.team-id", "TEAM")
        .withProperty(PREFIX + "apple.key-id", "KEY").withProperty(PREFIX + "apple.private-key", pem());
  }

  @Test void blankProvidersAreNotOfferedAndCallbacksUseTheRedirectBase() throws Exception {
    assertThat(new SocialSignInConfiguration(new MockEnvironment()).providers).isEmpty();
    assertThat(new SocialSignInConfiguration(production()).providers).as("no settings is valid in production").isEmpty();
    var configured = new SocialSignInConfiguration(apple(new MockEnvironment()
        .withProperty("case.recovery.public-url", "https://otdog.example/#ignored")
        .withProperty(PREFIX + "google.client-id", "google").withProperty(PREFIX + "google.client-secret", "secret")));
    assertThat(configured.providers).containsOnlyKeys(SocialProvider.GOOGLE, SocialProvider.APPLE);
    assertThat(configured.redirectUri(SocialProvider.APPLE)).isEqualTo("https://otdog.example/api/v1/account/social-sign-in/apple/callback");
    assertThat(new SocialSignInConfiguration(new MockEnvironment().withProperty(PREFIX + "google.client-id", "google")).providers)
        .as("partial settings outside production leave the provider off").isEmpty();
  }

  @Test void productionRefusesPartialSettingsPlainHttpAndEndpointOverrides() throws Exception {
    var https = "https://otdog.example/";
    assertThatThrownBy(() -> new SocialSignInConfiguration(production().withProperty(PREFIX + "redirect-base", https)
        .withProperty(PREFIX + "google.client-id", "google"))).hasMessageContaining("Google sign-in needs");
    assertThatThrownBy(() -> new SocialSignInConfiguration(production().withProperty(PREFIX + "redirect-base", https)
        .withProperty(PREFIX + "apple.client-id", "com.example.signin").withProperty(PREFIX + "apple.key-id", "KEY")))
        .hasMessageContaining("Apple sign-in needs");
    assertThatThrownBy(() -> new SocialSignInConfiguration(apple(production().withProperty(PREFIX + "redirect-base", "http://otdog.example/"))))
        .hasMessageContaining("HTTPS");
    assertThatThrownBy(() -> new SocialSignInConfiguration(apple(production().withProperty(PREFIX + "redirect-base", https)
        .withProperty(PREFIX + "apple.token-endpoint", "http://127.0.0.1:9/token")))).hasMessageContaining("overridden");
    assertThat(new SocialSignInConfiguration(apple(production().withProperty(PREFIX + "redirect-base", https))).providers)
        .containsOnlyKeys(SocialProvider.APPLE);
    assertThatThrownBy(() -> new SocialSignInConfiguration(new MockEnvironment().withProperty(PREFIX + "apple.client-id", "id")
        .withProperty(PREFIX + "apple.team-id", "TEAM").withProperty(PREFIX + "apple.key-id", "KEY")
        .withProperty(PREFIX + "apple.private-key", "not a key"))).hasMessageContaining("PEM");
  }

  @Test void derSignaturesBecomeJoseSignaturesTheJdkAccepts() throws Exception {
    var generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    var key = generator.generateKeyPair();
    for (int i = 0; i < 50; i++) {
      byte[] message = ("message " + i).getBytes(StandardCharsets.UTF_8);
      var signer = Signature.getInstance("SHA256withECDSA");
      signer.initSign(key.getPrivate()); signer.update(message);
      byte[] jose = SocialIdentityProvider.Apple.jose(signer.sign());
      assertThat(jose).hasSize(64);
      var verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
      verifier.initVerify(key.getPublic()); verifier.update(message);
      assertThat(verifier.verify(jose)).isTrue();
    }
    assertThatThrownBy(() -> SocialIdentityProvider.Apple.jose(new byte[] {0x30, 0x02, 0x02, 0x00})).isInstanceOf(IllegalStateException.class);
  }
}

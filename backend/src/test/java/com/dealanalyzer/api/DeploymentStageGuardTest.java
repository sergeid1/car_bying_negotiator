package com.dealanalyzer.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.dealanalyzer.buyingcase.persistence.CaseWriteGuard;
import com.dealanalyzer.deployment.DeploymentStage;
import com.dealanalyzer.listing.ListingEvidenceProvider;
import com.dealanalyzer.market.MarketDataProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

class DeploymentStageGuardTest {
  @TempDir Path temporaryDirectory;

  @ParameterizedTest
  @ValueSource(strings = {"spring.profiles.active=pilot", "spring.profiles.active=production",
      "spring.profiles.active=prod", "evidence.deployment=pilot", "evidence.deployment=production",
      "evidence.deployment=prod", "checkout.deployment=production", "inventory.deployment=pilot"})
  void everyGuardRefusesDevelopmentUnderEveryDeclaration(String declaration) throws Exception {
    var runner = new ApplicationContextRunner().withPropertyValues(declaration)
        .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
        .withBean(CaseWriteGuard.class, () -> mock(CaseWriteGuard.class));
    refuses(runner, "journey.vin.VinDecoderProductionGuard", "fake VIN decoder", "journey.vin.FakeVinDecoder");
    refuses(runner, "relay.RelayProductionGuard", "fake reply-intake provider", "relay.FakeDealerMailRelay");
    refuses(runner, "purchase.CheckoutProductionGuard", "DevelopmentNoChargeCheckout", "purchase.DevelopmentNoChargeCheckout");
    refuses(runner, "shopping.inventory.InventorySearchProductionGuard", "Development inventory adapter",
        "shopping.inventory.DeterministicFakeInventorySearchAdapter");
    refuses(runner.withBean(ListingEvidenceProvider.class, () -> mock(ListingEvidenceProvider.class)),
        "market.EvidenceProviderProductionGuard", "DeterministicFakeMarketDataProvider", "market.DeterministicFakeMarketDataProvider");
    refuses(runner.withBean(MarketDataProvider.class, () -> mock(MarketDataProvider.class)),
        "market.EvidenceProviderProductionGuard", "DeterministicListingEvidenceProvider", "listing.DeterministicListingEvidenceProvider");
    refuses(runner, "operations.EvidenceBudget", "evidence.budget.per-case-cost-usd");
    refuses(runner.withPropertyValues("account.session.cookie-secure=false"),
        "account.AccountSessionCookie", "cookies must be secure");
    refuses(runner, "buyingcase.SyntheticIntakeAffordance", "intake.synthetic.enabled=false");
    refuses(runner, "pilot.PilotReadiness", "unmet readiness prerequisites");
    refuses(runner, "document.DocumentParserProcess", "container");
    refuses(runner.withPropertyValues("document.malware-scanner=test"),
        "document.DeterministicMalwareScanner", "document.malware-scanner=test");
    refuses(runner, "document.EncryptedFileDocumentStorage", "DOCUMENT_STORAGE_ENCRYPTION_KEY");
    for (String key : List.of("", "short", "x".repeat(31))) {
      refuses(runner.withPropertyValues("document.storage.encryption-key=" + key),
          "document.EncryptedFileDocumentStorage", "DOCUMENT_STORAGE_ENCRYPTION_KEY");
    }
    refuses(runner.withPropertyValues("document.storage.encryption-key=" + "x".repeat(32)),
        "document.EncryptedFileDocumentStorage", "DOCUMENT_STORAGE_ENCRYPTION_KEY");
    byte[] randomKey = new byte[32]; new java.security.SecureRandom().nextBytes(randomKey);
    runner.withPropertyValues("document.storage.encryption-key=" + java.util.Base64.getEncoder().encodeToString(randomKey))
        .withUserConfiguration(type("document.EncryptedFileDocumentStorage"))
        .run(context -> assertThat(context).hasNotFailed());

    // The real application must also wire the refusal, rather than only passing isolated checks.
    assertThatThrownBy(() -> new HttpAcceptanceHarness(temporaryDirectory).start("stage", false,
        "--" + declaration)).hasStackTraceContaining("DOCUMENT_STORAGE_ENCRYPTION_KEY");
  }

  @Test void developmentRetainsLazyKeyValidationAndLocalAdapters() throws Exception {
    var runner = new ApplicationContextRunner()
        .withBean(CaseWriteGuard.class, () -> mock(CaseWriteGuard.class))
        .withPropertyValues("document.malware-scanner=test", "account.session.cookie-secure=false")
        .withUserConfiguration(type("document.EncryptedFileDocumentStorage"),
            type("document.DeterministicMalwareScanner"), type("document.DocumentParserProcess"),
            type("buyingcase.SyntheticIntakeAffordance"), type("account.AccountSessionCookie"));
    runner.run(context -> assertThat(context).hasNotFailed());
  }

  @Test void declarationsAreTrimmedCaseInsensitiveAndCannotBeWeakened() {
    for (String value : List.of(" PiLoT ", " PRODUCTION ", " pRoD ")) {
      var profile = new MockEnvironment();
      profile.setActiveProfiles(value, "development");
      assertThat(DeploymentStage.from(profile).productionLike()).isTrue();
      for (String property : List.of("evidence.deployment", "checkout.deployment", "inventory.deployment")) {
        assertThat(DeploymentStage.from(new MockEnvironment().withProperty(property, value)).productionLike()).isTrue();
      }
    }
    var environment = new MockEnvironment().withProperty("evidence.deployment", "development");
    environment.setActiveProfiles("production");
    assertThat(DeploymentStage.from(environment).productionLike()).isTrue();
    assertThat(DeploymentStage.from(new MockEnvironment()).productionLike()).isFalse();
  }

  @Test void onlyDeploymentPackageMayInterpretDeploymentDeclarations() throws Exception {
    var forbidden = java.util.regex.Pattern.compile(
        "\\b(?:getActiveProfiles|matchesProfiles|acceptsProfiles)\\s*\\(|"
            + "(?:evidence|checkout|inventory)\\.deployment");
    try (var sources = Files.walk(Path.of("src/main/java/com/dealanalyzer"))) {
      for (Path source : sources.filter(path -> path.toString().endsWith(".java"))
          .filter(path -> !path.startsWith(Path.of("src/main/java/com/dealanalyzer/deployment"))).toList()) {
        assertThat(forbidden.matcher(Files.readString(source)).find())
            .as("%s must use DeploymentStage instead of interpreting deployment declarations", source).isFalse();
      }
    }
  }

  private static void refuses(ApplicationContextRunner runner, String guard, String message, String... adapters)
      throws Exception {
    var classes = new java.util.ArrayList<Class<?>>();
    classes.add(type(guard));
    for (String adapter : adapters) classes.add(type(adapter));
    runner.withUserConfiguration(classes.toArray(Class<?>[]::new)).run(context -> {
      assertThat(context).hasFailed();
      assertThat(context.getStartupFailure()).hasStackTraceContaining(message);
    });
  }

  private static Class<?> type(String name) throws ClassNotFoundException {
    return Class.forName("com.dealanalyzer." + name);
  }
}

package com.dealanalyzer.api;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecurityHardeningAcceptanceTest {
  @TempDir Path directory;

  @Test void abuseIsBoundedEvenWhenForwardedAddressesAndTokensRotate() {
    try (var app = new HttpAcceptanceHarness(directory).start("rate-limit", false,
        "--security.rate-limit.requests=2", "--security.rate-limit.window=PT1M")) {
      for (int i = 0; i < 3; i++) {
        int attempt = i;
        app.client().get().uri("/api/v1/cases/missing")
            .header("X-Forwarded-For", "192.0.2." + i)
            .header("Case-Access-Token", "rotated-" + i)
            .exchange((request, response) -> {
              assertThat(response.getStatusCode().value()).isEqualTo(attempt < 2 ? 404 : 429);
              assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
              assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
              if (attempt == 2) {
                assertThat(Integer.parseInt(response.getHeaders().getFirst("Retry-After"))).isPositive();
                assertThat(new String(response.getBody().readAllBytes())).doesNotContain("rotated", "192.0.2");
              }
              return null;
            });
      }
    }
  }
}

package com.dealanalyzer.document;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ContainerParserRuntimeTest {
  @Test void pilotCannotSilentlyUseUnconfinedParser() {
    var environment = new MockEnvironment();
    environment.setActiveProfiles("pilot");
    assertThatThrownBy(() -> new DocumentParserProcess(environment))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("confined");
  }

  @Test void mutableImageOrRemoteDaemonIsRejectedAtStartup() {
    var environment = new MockEnvironment()
        .withProperty("document.parser.mode", "container")
        .withProperty("document.parser.container.executable", "/usr/bin/docker")
        .withProperty("document.parser.container.endpoint", "unix:///var/run/docker.sock")
        .withProperty("document.parser.container.image", "parser:latest");
    assertThatThrownBy(() -> new DocumentParserProcess(environment)).isInstanceOf(IllegalArgumentException.class);
    environment.withProperty("document.parser.container.image", "sha256:" + "a".repeat(64))
        .withProperty("document.parser.container.endpoint", "tcp://localhost:2375");
    assertThatThrownBy(() -> new DocumentParserProcess(environment)).isInstanceOf(IllegalArgumentException.class);
  }
}

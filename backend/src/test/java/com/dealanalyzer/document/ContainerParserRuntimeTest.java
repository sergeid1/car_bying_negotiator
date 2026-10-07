package com.dealanalyzer.document;

import static org.assertj.core.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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

  @Test void removalWaitsForAnAutoRemovalAlreadyInProgress(@TempDir Path directory) throws Exception {
    // rm loses the race to --rm and fails; the container stays listed for two checks while Docker removes it.
    var runtime = runtime(fakeDocker(directory, "1", "[ \"$n\" -le 2 ] && echo abc123\nexit 0"));
    runtime.remove("document-parser-test", directory);
    assertThat(Files.readString(directory.resolve("ps-count")).trim()).isEqualTo("3");
  }

  @Test void removalThatCannotBeListedKeepsTheParserClosed(@TempDir Path directory) throws Exception {
    var runtime = runtime(fakeDocker(directory, "0", "exit 1"));
    assertThatThrownBy(() -> runtime.remove("document-parser-test", directory))
        .isInstanceOf(IOException.class).hasMessageContaining("could not be verified");
  }

  private static ContainerParserRuntime runtime(Path executable) {
    return new ContainerParserRuntime(new MockEnvironment()
        .withProperty("document.parser.container.executable", executable.toString())
        .withProperty("document.parser.container.endpoint", "unix:///var/run/docker.sock")
        .withProperty("document.parser.container.image", "sha256:" + "a".repeat(64)));
  }

  /** The parser clears the environment, so the script uses only shell built-ins and its working directory. */
  private static Path fakeDocker(Path directory, String rmExit, String psBody) throws IOException {
    Path script = directory.resolve("docker");
    Files.writeString(script, """
        #!/bin/sh
        case "$3" in
          rm) exit %s ;;
          ps) n=0; [ -f ps-count ] && read n < ps-count; n=$((n + 1)); echo "$n" > ps-count
              %s ;;
        esac
        exit 2
        """.formatted(rmExit, psBody.replace("\n", "\n      ")));
    Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
    return script;
  }
}

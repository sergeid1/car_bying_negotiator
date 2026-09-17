package com.dealanalyzer.document;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.core.env.Environment;

/** Trusted launcher configuration; documents cannot supply an image, mount or runtime option. */
final class ContainerParserRuntime {
  private final String executable;
  private final String endpoint;
  private final String image;

  ContainerParserRuntime(Environment environment) {
    executable = environment.getRequiredProperty("document.parser.container.executable");
    endpoint = environment.getRequiredProperty("document.parser.container.endpoint");
    image = environment.getRequiredProperty("document.parser.container.image");
    if (!Path.of(executable).isAbsolute() || !endpoint.startsWith("unix:///")
        || !image.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Parser runtime requires an absolute executable, local Unix socket and immutable image ID");
    }
  }

  List<String> command(String name, int heapMb, Duration timeout) {
    var command = new ArrayList<>(List.of(executable, "--host", endpoint,
        "run", "--rm", "--pull", "never", "--name", name, "-i",
        "--network", "none", "--read-only", "--user", "10001:10001",
        "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
        "--memory", "1536m", "--memory-swap", "1536m", "--cpus", "1",
        "--pids-limit", "64", "--ulimit", "core=0", "--ulimit", "nofile=128:128",
        "--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size=128m,mode=1777",
        "--shm-size", "1m", "--log-driver", "none",
        "--entrypoint", "/usr/bin/timeout", image,
        "--signal=KILL", Long.toString(Math.max(1, timeout.toSeconds())),
        "/opt/java/openjdk/bin/java", "-Xmx" + heapMb + "m",
        "-XX:MaxDirectMemorySize=32m", "-XX:MaxMetaspaceSize=128m",
        "-XX:ReservedCodeCacheSize=64m", "-XX:ActiveProcessorCount=1",
        "-XX:+ExitOnOutOfMemoryError", "-XX:-CreateCoredumpOnCrash",
        "-XX:ErrorFile=/tmp/jvm-error.log", "-Djava.awt.headless=true",
        "-Djava.io.tmpdir=/tmp", "-Duser.home=/tmp",
        "-Dloader.main=" + DocumentParserWorker.class.getName(),
        "-cp", "/app/application.jar", "org.springframework.boot.loader.launch.PropertiesLauncher"));
    return List.copyOf(command);
  }

  void remove(String name, Path directory) throws IOException {
    // Docker owns the worker, so killing its CLI alone does not stop native descendants.
    Process cleanup = DocumentParserProcess.start(List.of(executable, "--host", endpoint,
        "rm", "--force", name), directory);
    boolean interrupted = Thread.interrupted();
    try {
      if (!cleanup.waitFor(5, TimeUnit.SECONDS)) throw new IOException("Parser runtime cleanup timed out");
      // Auto-removal can win this race. Verify absence instead of trusting rm's exit code.
      Process check = DocumentParserProcess.start(List.of(executable, "--host", endpoint,
          "ps", "--all", "--quiet", "--filter", "name=^/" + name + "$"), directory);
      try {
        if (!check.waitFor(5, TimeUnit.SECONDS) || check.exitValue() != 0
            || check.getInputStream().read() != -1) throw new IOException("Parser removal could not be verified");
      } finally { check.destroyForcibly(); }
    } catch (InterruptedException stopped) {
      interrupted = true;
      throw new IOException("Parser runtime cleanup interrupted");
    } finally {
      cleanup.destroyForcibly();
      if (interrupted) Thread.currentThread().interrupt();
    }
  }
}

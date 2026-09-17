package com.dealanalyzer.document;

import static org.assertj.core.api.Assertions.*;
import java.io.*;
import java.nio.file.Path;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class DocumentParserPackagingIT {
  @TempDir Path directory;

  @Test void executableBootJarCanLaunchTheDisposableParserWithoutStartingSpring() throws Exception {
    Path jar = Path.of("target/ai-deal-analyzer-0.1.0-SNAPSHOT.jar").toAbsolutePath();
    assertThat(jar).exists();
    var command = DocumentParserProcess.command(directory, 128, jar.toString());
    Process child = DocumentParserProcess.start(command, directory);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var result = executor.submit(() -> {
        try (var output = new DataOutputStream(child.getOutputStream());
             var input = new DataInputStream(child.getInputStream())) {
          output.writeUTF("inspect");
          DocumentParserProcess.Settings.from(new MockEnvironment()).write(output);
          output.writeInt(0);
          byte[] png = DocumentParserProcessTest.png(2, 2);
          output.writeInt(png.length); output.write(png); output.flush();
          assertThat(input.readUnsignedByte()).isZero();
          assertThat(input.readUTF()).isEqualTo("image/png");
          assertThat(input.readInt()).isEqualTo(1);
          assertThat(input.readInt()).isEqualTo(2); assertThat(input.readInt()).isEqualTo(2);
          assertThat(input.readBoolean()).isFalse(); assertThat(input.readBoolean()).isFalse(); assertThat(input.readBoolean()).isFalse();
          assertThat(input.read()).isEqualTo(-1);
          assertThat(child.waitFor()).isZero();
        }
        return true;
      });
      try { assertThat(result.get(10, TimeUnit.SECONDS)).isTrue(); }
      finally { child.destroyForcibly(); }
    } finally { child.destroyForcibly(); }
  }
}

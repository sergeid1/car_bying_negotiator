package com.dealanalyzer.compensation;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** All production packages outside compensation are protected, including future result-writing packages. */
class CompensationArchitectureTest {
  private static void assertIndependent(byte[] bytecode) {
    // Class-file UTF8 constants include imports used in signatures, annotations, fields and method bodies.
    String constants = new String(bytecode, StandardCharsets.ISO_8859_1);
    assertThat(constants).doesNotContain("com/dealanalyzer/compensation", "com.dealanalyzer.compensation");
  }

  @Test void noProductionCodeOutsideCompensationCanReadIt() throws Exception {
    Path root = Path.of("target/classes/com/dealanalyzer");
    try (var paths = Files.walk(root)) {
      var classes = paths.filter(path -> path.toString().endsWith(".class"))
          .filter(path -> !path.startsWith(root.resolve("compensation"))).toList();
      assertThat(classes).isNotEmpty();
      for (var path : classes) {
        try { assertIndependent(Files.readAllBytes(path)); }
        catch (AssertionError failure) { throw new AssertionError("Compensation dependency in " + path, failure); }
      }
    }
  }

  @Test void deliberateCompiledDependencyIsRejected() throws Exception {
    try (var fixture = getClass().getResourceAsStream("/com/dealanalyzer/architecturefixture/CompensationDependent.class")) {
      assertThat(fixture).isNotNull();
      byte[] bytes = fixture.readAllBytes();
      assertThatThrownBy(() -> assertIndependent(bytes)).isInstanceOf(AssertionError.class);
    }
  }
}

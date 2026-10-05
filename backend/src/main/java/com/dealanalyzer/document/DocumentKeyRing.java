package com.dealanalyzer.document;

import com.dealanalyzer.deployment.DeploymentStage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.springframework.core.env.Environment;

/** Immutable keys. The legacy secret is only for headerless objects once an active ID is set. */
final class DocumentKeyRing {
  static final String LEGACY = "legacy";
  private final Map<String, byte[]> keys = new HashMap<>();
  final String activeId;

  DocumentKeyRing(String legacy, Environment environment) {
    activeId = environment.getProperty("document.storage.active-key-id", "").trim();
    String configured = environment.getProperty("document.storage.keys", "");
    if (!configured.isBlank()) for (String entry : configured.split(",", -1)) {
      int delimiter = entry.indexOf('=');
      if (delimiter < 1) throw invalid();
      String id = entry.substring(0, delimiter).trim();
      if (!id.matches("[A-Za-z0-9_-]{1,64}") || id.equals(LEGACY)) throw invalid();
      byte[] material;
      try { material = Base64.getDecoder().decode(entry.substring(delimiter + 1).trim()); }
      catch (IllegalArgumentException failure) { throw invalid(); }
      // Entropy cannot be proven from bytes; reject obvious text/passphrase and repeated material.
      if (material.length < 32 || textLike(material)
          || java.util.stream.IntStream.range(0, material.length).map(i -> material[i] & 255).distinct().count() < 16
          || keys.putIfAbsent(id, sha256(material)) != null) throw invalid();
    }
    if (!activeId.isEmpty() && !keys.containsKey(activeId)) throw invalid();
    if (activeId.isEmpty() && !keys.isEmpty()) throw invalid();
    if (!legacy.isEmpty()) {
      if (legacy.getBytes(StandardCharsets.UTF_8).length < 32) {
        if (DeploymentStage.from(environment).productionLike()) throw invalid();
      } else {
        if (DeploymentStage.from(environment).productionLike()) {
          byte[] decoded;
          try { decoded = Base64.getDecoder().decode(legacy); }
          catch (IllegalArgumentException failure) { throw invalid(); }
          if (decoded.length < 32 || textLike(decoded)
              || java.util.stream.IntStream.range(0, decoded.length).map(i -> decoded[i] & 255).distinct().count() < 16)
            throw invalid();
        }
        keys.put(LEGACY, sha256(legacy.getBytes(StandardCharsets.UTF_8)));
      }
    }
    if (DeploymentStage.from(environment).productionLike() && activeId.isEmpty() && !keys.containsKey(LEGACY))
      throw invalid();
  }

  String writingId() { return activeId.isEmpty() ? LEGACY : activeId; }
  byte[] key(String id) {
    byte[] key = keys.get(id);
    if (key == null) throw new DocumentUnavailableException("DOCUMENT_KEY_UNAVAILABLE");
    return key;
  }
  private static boolean textLike(byte[] bytes) {
    int printable = 0;
    for (byte value : bytes) if (value >= 32 && value <= 126) printable++;
    return printable >= bytes.length * 0.85;
  }
  private static IllegalStateException invalid() {
    return new IllegalStateException("DOCUMENT_STORAGE_ENCRYPTION_KEY / document.storage.keys require random base64 key material (at least 32 bytes), unique IDs and a configured active ID");
  }
  private static byte[] sha256(byte[] material) {
    try { return MessageDigest.getInstance("SHA-256").digest(material); }
    catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 unavailable", failure); }
  }
}

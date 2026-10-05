package com.dealanalyzer.document;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.dealanalyzer.buyingcase.persistence.CaseWriteGuard;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class EncryptedFileDocumentStorageTest {
  @TempDir Path objects;
  private static final String LEGACY = "old-development-secret-at-least-32-bytes";
  private static final byte[] CONTENT = "private document".getBytes(StandardCharsets.UTF_8);
  private static String material() {
    byte[] key = new byte[32]; new java.security.SecureRandom().nextBytes(key);
    return Base64.getEncoder().encodeToString(key);
  }
  private EncryptedFileDocumentStorage storage(String active, String keys) {
    return new EncryptedFileDocumentStorage(objects, LEGACY, mock(CaseWriteGuard.class),
        new MockEnvironment().withProperty("document.storage.active-key-id", active)
            .withProperty("document.storage.keys", keys));
  }

  @Test void selectsRecordedKeysForOriginalsAndPagesAndFailsClosedOnMissingKeysAndSubstitution() throws Exception {
    String old = material(), current = material();
    var first = storage("first", "first=" + old);
    var original = first.store("case", "document", CONTENT);
    var page = first.storeNormalized("case", "document", 1, CONTENT);
    assertThat(original.keyReference()).isEqualTo("local-kek:first:v2");
    var second = storage("second", "first=" + old + ",second=" + current);
    assertThat(second.readOriginal("case", "document", original.objectKey())).isEqualTo(CONTENT);
    assertThat(second.readNormalized("case", "document", 1, page.objectKey())).isEqualTo(CONTENT);
    assertThat(second.store("case", "new", CONTENT).keyReference()).isEqualTo("local-kek:second:v2");
    assertThatThrownBy(() -> second.readOriginal("other", "document", original.objectKey()))
        .isInstanceOf(DocumentUnavailableException.class);
    assertThatThrownBy(() -> second.readNormalized("case", "document", 2, page.objectKey()))
        .isInstanceOf(DocumentUnavailableException.class);
    assertThatThrownBy(() -> storage("second", "second=" + current).readOriginal("case", "document", original.objectKey()))
        .isInstanceOf(DocumentUnavailableException.class).hasMessageNotContaining("first");
    // A configured ID with the same material still cannot replace the authenticated ID.
    byte[] bytes = Files.readAllBytes(objects.resolve(original.objectKey()));
    System.arraycopy("third".getBytes(StandardCharsets.US_ASCII), 0, bytes, 10, 5);
    Files.write(objects.resolve(original.objectKey()), bytes);
    assertThatThrownBy(() -> storage("third", "third=" + old).readOriginal("case", "document", original.objectKey()))
        .isInstanceOf(DocumentUnavailableException.class);
    Files.write(objects.resolve(original.objectKey()), new byte[]{'D','O','C','K','E','Y','S','!',2});
    assertThatThrownBy(() -> second.readOriginal("case", "document", original.objectKey()))
        .isInstanceOf(DocumentUnavailableException.class);
  }

  @Test void readsRealHeaderlessLegacyFormatAndRotatesBothKindsWithoutLegacyKey() throws Exception {
    byte[] iv = new byte[12]; new java.security.SecureRandom().nextBytes(iv);
    var cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(java.security.MessageDigest.getInstance("SHA-256")
        .digest(LEGACY.getBytes(StandardCharsets.UTF_8)), "AES"), new GCMParameterSpec(128, iv));
    cipher.updateAAD("case:document".getBytes(StandardCharsets.UTF_8));
    byte[] ciphertext = cipher.doFinal(CONTENT), blob = Arrays.copyOf(iv, iv.length + ciphertext.length);
    System.arraycopy(ciphertext, 0, blob, iv.length, ciphertext.length);
    Files.write(objects.resolve("original.enc"), blob);
    String key = material(); var target = storage("active", "active=" + key);
    assertThat(target.readOriginal("case", "document", "original.enc")).isEqualTo(CONTENT);
    var page = storage("", "").storeNormalized("case", "document", 1, CONTENT);
    assertThat(target.rotate("case:document", "original.enc")).isTrue();
    assertThat(target.rotate("case:document:page:1", page.objectKey())).isTrue();
    assertThat(target.rotate("case:document", "original.enc")).isFalse();
    var withoutLegacy = new EncryptedFileDocumentStorage(objects, "", mock(CaseWriteGuard.class),
        new MockEnvironment().withProperty("document.storage.active-key-id", "active")
            .withProperty("document.storage.keys", "active=" + key));
    assertThat(withoutLegacy.readOriginal("case", "document", "original.enc")).isEqualTo(CONTENT);
    assertThat(withoutLegacy.readNormalized("case", "document", 1, page.objectKey())).isEqualTo(CONTENT);
  }

  @Test void rejectsMalformedWeakDuplicateAndMissingActiveConfigurationAtStartup() {
    for (String keys : java.util.List.of("bad", "id=oops", "id=" + Base64.getEncoder().encodeToString(new byte[31]),
        "id=" + Base64.getEncoder().encodeToString("a human chosen passphrase of adequate length".getBytes(StandardCharsets.UTF_8)),
        "id=" + material() + ",id=" + material(), "legacy=" + material())) {
      assertThatThrownBy(() -> storage("id", keys)).isInstanceOf(IllegalStateException.class);
    }
    assertThatThrownBy(() -> storage("missing", "id=" + material())).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new EncryptedFileDocumentStorage(objects, LEGACY, mock(CaseWriteGuard.class),
        new MockEnvironment().withProperty("evidence.deployment", "pilot"))).isInstanceOf(IllegalStateException.class);
  }

  @Test void boundedRotationRepairsMetadataAfterInterruptedReplacementAndRescansSafely() throws Exception {
    String old = material(), current = material();
    var source = storage("old", "old=" + old);
    var original = source.store("case", "doc", CONTENT);
    var page = source.storeNormalized("case", "doc", 1, CONTENT);
    var target = storage("new", "new=" + current + ",old=" + old);
    try (var database = DriverManager.getConnection("jdbc:h2:mem:" + java.util.UUID.randomUUID())) {
      try (var statement = database.createStatement()) {
        statement.execute("create table source_documents(id varchar,case_id varchar,object_key varchar,original_deleted_at timestamp,encryption_key_reference varchar)");
        statement.execute("create table document_pages(document_id varchar,page_number int,normalized_object_key varchar,normalized_deleted_at timestamp)");
        statement.execute("insert into source_documents values('doc','case','" + original.objectKey() + "',null,'old')");
        statement.execute("insert into document_pages values('doc',1,'" + page.objectKey() + "',null)");
      }
      // Simulate process death between atomic file replacement and database reference update.
      target.rotate("case:doc", original.objectKey());
      var first = DocumentKeyRotation.rotateBatch(database, target, "", 1);
      assertThat(first.scanned()).isEqualTo(1);
      var second = DocumentKeyRotation.rotateBatch(database, target, first.cursor(), 1);
      assertThat(second.scanned()).isEqualTo(1);
      assertThat(DocumentKeyRotation.rotateBatch(database, target, second.cursor(), 1).scanned()).isZero();
      assertThat(DocumentKeyRotation.rotateBatch(database, target, "", 100).rotated()).isZero();
      try (var statement = database.createStatement(); var result = statement.executeQuery("select encryption_key_reference from source_documents")) {
        result.next(); assertThat(result.getString(1)).isEqualTo("local-kek:new:v2");
      }
      assertThat(target.readOriginal("case", "doc", original.objectKey())).isEqualTo(CONTENT);
      assertThat(target.readNormalized("case", "doc", 1, page.objectKey())).isEqualTo(CONTENT);
    }
  }

  @Test void operatorCommandRotatesInBatchesAndReportsOnlyCountsAcrossRestart() throws Exception {
    String old = material(), current = material();
    var source = storage("old", "old=" + old);
    var original = source.store("case", "doc", CONTENT);
    var page = source.storeNormalized("case", "doc", 1, CONTENT);
    String url = "jdbc:h2:file:" + objects.resolve("metadata").toAbsolutePath();
    try (var db = DriverManager.getConnection(url); var statement = db.createStatement()) {
      statement.execute("create table source_documents(id varchar,case_id varchar,object_key varchar,original_deleted_at timestamp,encryption_key_reference varchar)");
      statement.execute("create table document_pages(document_id varchar,page_number int,normalized_object_key varchar,normalized_deleted_at timestamp)");
      statement.execute("insert into source_documents values('doc','case','" + original.objectKey() + "',null,'old')");
      statement.execute("insert into document_pages values('doc',1,'" + page.objectKey() + "',null)");
    }
    for (int attempt = -1; attempt < 2; attempt++) {
      String classpath = Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
          .filter(entry -> !Path.of(entry).getFileName().toString().startsWith("commons-logging-"))
          .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
      var command = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
          "-cp", classpath, DocumentKeyRotation.class.getName(), "--service-stopped", "1");
      command.environment().put("DOCUMENT_STORAGE_PATH", objects.toString());
      command.environment().put("DOCUMENT_STORAGE_ACTIVE_KEY_ID", "new");
      command.environment().put("DOCUMENT_STORAGE_KEYS", (attempt == -1 ? "" : "old=" + old + ",") + "new=" + current);
      command.environment().remove("DOCUMENT_STORAGE_ENCRYPTION_KEY");
      command.environment().put("SPRING_DATASOURCE_URL", url);
      command.environment().put("SPRING_DATASOURCE_USERNAME", "");
      command.environment().put("SPRING_DATASOURCE_PASSWORD", "");
      var process = command.redirectErrorStream(true).start();
      assertThat(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (attempt == -1) {
        assertThat(process.exitValue()).isEqualTo(1);
        assertThat(output.trim()).isEqualTo("Document key rotation failed; preserve all keys and retry after repair.");
        assertThat(source.readOriginal("case", "doc", original.objectKey())).isEqualTo(CONTENT);
        assertThat(source.readNormalized("case", "doc", 1, page.objectKey())).isEqualTo(CONTENT);
        continue;
      }
      assertThat(process.exitValue()).isZero();
      assertThat(output.lines().toList()).containsExactly("scanned=1 rotated=" + (attempt == 0 ? 1 : 0),
          "scanned=1 rotated=" + (attempt == 0 ? 1 : 0), "scanned=0 rotated=0");
    }
    var target = storage("new", "new=" + current);
    assertThat(target.readOriginal("case", "doc", original.objectKey())).isEqualTo(CONTENT);
    assertThat(target.readNormalized("case", "doc", 1, page.objectKey())).isEqualTo(CONTENT);
  }
}

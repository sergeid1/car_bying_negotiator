package com.dealanalyzer.document;

import com.dealanalyzer.buyingcase.persistence.CaseWriteGuard;
import com.dealanalyzer.deployment.DeploymentStage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "document.storage.provider", havingValue = "file", matchIfMissing = true)
class EncryptedFileDocumentStorage extends EncryptedDocumentStorage {
  private final Path storagePath;

  EncryptedFileDocumentStorage(
      @Value("${document.storage.path:./var/document-objects}") Path storagePath,
      @Value("${document.storage.encryption-key:}") String configuredKey,
      CaseWriteGuard guard, Environment environment) {
    super(configuredKey, guard, environment);
    this.storagePath = storagePath;
    if (DeploymentStage.from(environment).productionLike()
        && !environment.getProperty("document.storage.allow-file", Boolean.class, false))
      throw new IllegalStateException("Pilot/production requires S3 storage or explicit document.storage.allow-file=true");
  }

  @Override void writeCiphertext(String objectKey, byte[] stored) throws IOException {
    Files.createDirectories(storagePath);
    Path temporary = Files.createTempFile(storagePath, objectKey, ".tmp");
    try {
      Files.write(temporary, stored);
      Files.move(temporary, storagePath.resolve(objectKey),
          StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally { Files.deleteIfExists(temporary); }
  }

  @Override byte[] readCiphertext(String objectKey) throws IOException {
    return Files.readAllBytes(storagePath.resolve(objectKey));
  }

  @Override public void delete(String objectKey) {
    try { Files.deleteIfExists(storagePath.resolve(objectKey)); }
    catch (IOException failure) {
      throw new IllegalStateException("Unable to remove unreferenced encrypted document");
    }
  }

  @Override public List<String> caseObjectKeys(String caseId) {
    try {
      if (Files.notExists(storagePath)) return List.of();
      try (var files = Files.list(storagePath)) {
        return files.map(path -> path.getFileName().toString())
            .filter(key -> key.startsWith(caseId + "-")).sorted().toList();
      }
    } catch (IOException failure) { throw new IllegalStateException("Artifact inventory unavailable"); }
  }

  @Override public boolean available() { return Files.exists(storagePath); }
}

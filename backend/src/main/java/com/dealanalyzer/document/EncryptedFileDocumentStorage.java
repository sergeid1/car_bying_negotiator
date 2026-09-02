package com.dealanalyzer.document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class EncryptedFileDocumentStorage implements DocumentObjectStorage {
  private static final int IV_BYTES = 12;

  private final Path storagePath;
  private final String configuredKey;

  EncryptedFileDocumentStorage(
      @Value("${document.storage.path:./var/document-objects}") Path storagePath,
      @Value("${document.storage.encryption-key:}") String configuredKey) {
    this.storagePath = storagePath;
    this.configuredKey = configuredKey;
  }

  @Override
  public StoredObject store(String caseId, String documentId, byte[] content) {
    return encrypt(caseId + ":" + documentId, caseId + "-" + documentId + ".enc", content);
  }

  @Override
  public byte[] readOriginal(String caseId, String documentId, String objectKey) {
    return decrypt(caseId + ":" + documentId, objectKey);
  }

  @Override
  public StoredObject storeNormalized(
      String caseId, String documentId, int pageNumber, byte[] content) {
    String objectKey = caseId + "-" + documentId + "-page-" + pageNumber + ".png.enc";
    return encrypt(caseId + ":" + documentId + ":page:" + pageNumber, objectKey, content);
  }

  @Override
  public byte[] readNormalized(
      String caseId, String documentId, int pageNumber, String objectKey) {
    return decrypt(caseId + ":" + documentId + ":page:" + pageNumber, objectKey);
  }

  private StoredObject encrypt(String associatedData, String objectKey, byte[] content) {
    if (configuredKey.getBytes(StandardCharsets.UTF_8).length < 32) {
      throw new IllegalStateException("DOCUMENT_STORAGE_ENCRYPTION_KEY must contain at least 32 bytes");
    }
    try {
      Files.createDirectories(storagePath);
      byte[] key = Arrays.copyOf(
          MessageDigest.getInstance("SHA-256").digest(configuredKey.getBytes(StandardCharsets.UTF_8)),
          32);
      byte[] iv = new byte[IV_BYTES];
      java.security.SecureRandom.getInstanceStrong().nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
      cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
      byte[] encrypted = cipher.doFinal(content);
      byte[] stored = ByteBufferSupport.join(iv, encrypted);
      Path temporary = Files.createTempFile(storagePath, objectKey, ".tmp");
      Files.write(temporary, stored);
      try {
        Files.move(
            temporary,
            storagePath.resolve(objectKey),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
        Files.move(
            temporary,
            storagePath.resolve(objectKey),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
      String keyReference = "local-kek:" + MessageDigest.getInstance("SHA-256")
          .digest(key)[0] + ":v1";
      return new StoredObject(objectKey, keyReference);
    } catch (IOException | GeneralSecurityException exception) {
      throw new IllegalStateException("Unable to encrypt and store document", exception);
    }
  }

  private byte[] decrypt(String associatedData, String objectKey) {
    if (configuredKey.getBytes(StandardCharsets.UTF_8).length < 32) {
      throw new IllegalStateException("DOCUMENT_STORAGE_ENCRYPTION_KEY must contain at least 32 bytes");
    }
    try {
      byte[] stored = Files.readAllBytes(storagePath.resolve(objectKey));
      if (stored.length <= IV_BYTES) {
        throw new IllegalStateException("Stored document is truncated");
      }
      byte[] key = Arrays.copyOf(
          MessageDigest.getInstance("SHA-256").digest(configuredKey.getBytes(StandardCharsets.UTF_8)),
          32);
      byte[] iv = Arrays.copyOf(stored, IV_BYTES);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
      cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
      return cipher.doFinal(Arrays.copyOfRange(stored, IV_BYTES, stored.length));
    } catch (IOException | GeneralSecurityException exception) {
      throw new IllegalStateException("Unable to read encrypted document", exception);
    }
  }

  @Override
  public void delete(String objectKey) {
    try {
      Files.deleteIfExists(storagePath.resolve(objectKey));
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to remove unreferenced encrypted document", exception);
    }
  }

  private static final class ByteBufferSupport {
    private ByteBufferSupport() {}

    static byte[] join(byte[] first, byte[] second) {
      byte[] joined = Arrays.copyOf(first, first.length + second.length);
      System.arraycopy(second, 0, joined, first.length, second.length);
      return joined;
    }
  }
}

package com.dealanalyzer.document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.Environment;

abstract class EncryptedDocumentStorage implements DocumentObjectStorage {
  private static final int IV_BYTES = 12;

  private final com.dealanalyzer.buyingcase.persistence.CaseWriteGuard guard;
  private static final byte[] MAGIC = {'D', 'O', 'C', 'K', 'E', 'Y', 'S', '!', 1};
  private final DocumentKeyRing keys;

  EncryptedDocumentStorage(
      String configuredKey,
      com.dealanalyzer.buyingcase.persistence.CaseWriteGuard guard, Environment environment) {
    this.guard = guard;
    this.keys = new DocumentKeyRing(configuredKey, environment);
  }

  @Override
  @org.springframework.transaction.annotation.Transactional
  public StoredObject store(String caseId, String documentId, byte[] content) {
    guard.lockActive(caseId);
    return encrypt(caseId + ":" + documentId, caseId + "-" + documentId + ".enc", content);
  }

  @Override
  public byte[] readOriginal(String caseId, String documentId, String objectKey) {
    return decrypt(caseId + ":" + documentId, objectKey);
  }

  @Override
  @org.springframework.transaction.annotation.Transactional
  public StoredObject storeNormalized(
      String caseId, String documentId, int pageNumber, byte[] content) {
    guard.lockActive(caseId);
    String objectKey = caseId + "-" + documentId + "-page-" + pageNumber + ".png.enc";
    return encrypt(caseId + ":" + documentId + ":page:" + pageNumber, objectKey, content);
  }

  @Override
  public byte[] readNormalized(
      String caseId, String documentId, int pageNumber, String objectKey) {
    return decrypt(caseId + ":" + documentId + ":page:" + pageNumber, objectKey);
  }

  private StoredObject encrypt(String associatedData, String objectKey, byte[] content) {
    try {
      String id = keys.writingId();
      byte[] key = keys.key(id);
      byte[] header = header(id);
      byte[] iv = new byte[IV_BYTES];
      java.security.SecureRandom.getInstanceStrong().nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
      cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
      if (header.length > 0) cipher.updateAAD(header);
      byte[] encrypted = cipher.doFinal(content);
      byte[] stored = ByteBufferSupport.join(header, ByteBufferSupport.join(iv, encrypted));
      writeCiphertext(objectKey, stored);
      return new StoredObject(objectKey, "local-kek:" + id + ":v2");
    } catch (IOException | GeneralSecurityException exception) {
      throw new IllegalStateException("Unable to encrypt and store document", exception);
    }
  }

  private byte[] decrypt(String associatedData, String objectKey) {
    try {
      byte[] stored = readCiphertext(objectKey);
      int offset = headerLength(stored);
      String id = offset == 0 ? DocumentKeyRing.LEGACY
          : new String(stored, MAGIC.length + 1, offset - MAGIC.length - 1, StandardCharsets.US_ASCII);
      if (stored.length < offset + IV_BYTES + 16) throw new DocumentUnavailableException("DOCUMENT_UNREADABLE");
      byte[] key = keys.key(id);
      byte[] iv = Arrays.copyOfRange(stored, offset, offset + IV_BYTES);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
      cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
      if (offset > 0) cipher.updateAAD(Arrays.copyOf(stored, offset));
      return cipher.doFinal(Arrays.copyOfRange(stored, offset + IV_BYTES, stored.length));
    } catch (IOException | GeneralSecurityException exception) {
      throw new DocumentUnavailableException("DOCUMENT_UNREADABLE");
    }
  }

  private static byte[] header(String id) {
    byte[] prefix = Arrays.copyOf(MAGIC, MAGIC.length + 1);
    prefix[MAGIC.length] = (byte) id.length();
    return ByteBufferSupport.join(prefix, id.getBytes(StandardCharsets.US_ASCII));
  }

  private static int headerLength(byte[] stored) {
    int marker = MAGIC.length - 1;
    if (stored.length < marker || !Arrays.equals(Arrays.copyOf(stored, marker), Arrays.copyOf(MAGIC, marker))) return 0;
    if (stored.length < MAGIC.length + 1 || stored[marker] != 1)
      throw new DocumentUnavailableException("DOCUMENT_UNREADABLE");
    int length = stored[MAGIC.length] & 255;
    if (length < 1 || length > 64 || stored.length < MAGIC.length + 1 + length)
      throw new DocumentUnavailableException("DOCUMENT_UNREADABLE");
    return MAGIC.length + 1 + length;
  }

  /** Offline operator only. Replacement is atomic; retry after interruption is safe. */
  boolean rotate(String associatedData, String objectKey) {
    if (keys.activeId.isEmpty()) throw new IllegalStateException("Rotation requires document.storage.active-key-id");
    try {
      byte[] stored = readCiphertext(objectKey);
      int offset = headerLength(stored);
      String id = offset == 0 ? DocumentKeyRing.LEGACY
          : new String(stored, MAGIC.length + 1, offset - MAGIC.length - 1, StandardCharsets.US_ASCII);
      if (id.equals(keys.activeId)) { decrypt(associatedData, objectKey); return false; }
      encrypt(associatedData, objectKey, decrypt(associatedData, objectKey));
      return true;
    } catch (IOException failure) { throw new DocumentUnavailableException("DOCUMENT_UNREADABLE"); }
  }

  String activeKeyReference() { return "local-kek:" + keys.writingId() + ":v2"; }

  abstract void writeCiphertext(String objectKey, byte[] stored) throws IOException;

  abstract byte[] readCiphertext(String objectKey) throws IOException;

  private static final class ByteBufferSupport {
    private ByteBufferSupport() {}

    static byte[] join(byte[] first, byte[] second) {
      byte[] joined = Arrays.copyOf(first, first.length + second.length);
      System.arraycopy(second, 0, joined, first.length, second.length);
      return joined;
    }
  }
}

package com.dealanalyzer.relay;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Message subjects and bodies are AES-GCM encrypted at rest, bound to the case and message IDs. */
@Component
class RelayContentProtection {
  private final String secret;
  private final SecureRandom random = new SecureRandom();

  RelayContentProtection(@Value("${document.storage.encryption-key:}") String secret) { this.secret = secret; }

  String encrypt(String caseId, String messageId, String content) {
    if (content == null) return null;
    byte[] iv = new byte[12]; random.nextBytes(iv);
    byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, caseId, messageId, iv, content.getBytes(StandardCharsets.UTF_8));
    byte[] stored = Arrays.copyOf(iv, iv.length + encrypted.length);
    System.arraycopy(encrypted, 0, stored, iv.length, encrypted.length);
    return Base64.getEncoder().encodeToString(stored);
  }

  String decrypt(String caseId, String messageId, String stored) {
    if (stored == null) return null;
    byte[] bytes = Base64.getDecoder().decode(stored);
    if (bytes.length < 28) throw new IllegalStateException("Stored relay content is unreadable");
    return new String(crypt(Cipher.DECRYPT_MODE, caseId, messageId, Arrays.copyOf(bytes, 12),
        Arrays.copyOfRange(bytes, 12, bytes.length)), StandardCharsets.UTF_8);
  }

  private byte[] crypt(int mode, String caseId, String messageId, byte[] iv, byte[] content) {
    if (secret.getBytes(StandardCharsets.UTF_8).length < 32)
      throw new IllegalStateException("DOCUMENT_STORAGE_ENCRYPTION_KEY must contain at least 32 bytes");
    try {
      byte[] key = MessageDigest.getInstance("SHA-256")
          .digest(("dealer-relay-content.v1:" + secret).getBytes(StandardCharsets.UTF_8));
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
      cipher.updateAAD((caseId + ":" + messageId).getBytes(StandardCharsets.UTF_8));
      return cipher.doFinal(content);
    } catch (GeneralSecurityException failure) {
      // The cause is dropped: it could carry key material into a log.
      throw new IllegalStateException("Relay content protection failed");
    }
  }
}

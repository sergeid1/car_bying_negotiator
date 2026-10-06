package com.dealanalyzer.mailbox;

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

/**
 * Mailbox tokens, the sending address and PKCE verifiers are AES-GCM encrypted at rest with a dedicated key
 * ({@code mailbox.token-encryption-key}), not the document or contact keys, and bound to their row and purpose.
 */
@Component
class MailboxTokenProtection {
  private final String secret;
  private final SecureRandom random = new SecureRandom();

  MailboxTokenProtection(@Value("${mailbox.token-encryption-key:}") String secret) { this.secret = secret; }

  /** True when the key is long enough to use. */
  boolean configured() { return secret.getBytes(StandardCharsets.UTF_8).length >= 32; }

  String encrypt(String rowId, String purpose, String value) {
    byte[] iv = new byte[12]; random.nextBytes(iv);
    byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, rowId, purpose, iv, value.getBytes(StandardCharsets.UTF_8));
    byte[] stored = Arrays.copyOf(iv, iv.length + encrypted.length);
    System.arraycopy(encrypted, 0, stored, iv.length, encrypted.length);
    return Base64.getEncoder().encodeToString(stored);
  }

  String decrypt(String rowId, String purpose, String stored) {
    byte[] bytes = Base64.getDecoder().decode(stored);
    if (bytes.length < 28) throw new IllegalStateException("Stored mailbox value is unreadable");
    return new String(crypt(Cipher.DECRYPT_MODE, rowId, purpose, Arrays.copyOf(bytes, 12),
        Arrays.copyOfRange(bytes, 12, bytes.length)), StandardCharsets.UTF_8);
  }

  private byte[] crypt(int mode, String rowId, String purpose, byte[] iv, byte[] content) {
    if (!configured()) throw new IllegalStateException("MAILBOX_TOKEN_ENCRYPTION_KEY must contain at least 32 bytes");
    try {
      byte[] key = MessageDigest.getInstance("SHA-256")
          .digest(("mailbox-token.v1:" + secret).getBytes(StandardCharsets.UTF_8));
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
      cipher.updateAAD((rowId + ":" + purpose).getBytes(StandardCharsets.UTF_8));
      return cipher.doFinal(content);
    } catch (GeneralSecurityException failure) {
      // The cause is dropped: it could carry key material into a log.
      throw new IllegalStateException("Mailbox token protection failed");
    }
  }
}

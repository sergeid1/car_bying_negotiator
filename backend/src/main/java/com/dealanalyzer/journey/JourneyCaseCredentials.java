package com.dealanalyzer.journey;

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

/** Allows safe replay of a pick; the original case link is encrypted and never exported. */
@Component
class JourneyCaseCredentials {
  private final String secret;
  JourneyCaseCredentials(@Value("${document.storage.encryption-key:}") String secret) { this.secret = secret; }
  String encrypt(String accountId, String caseId, String token) {
    byte[] iv = new byte[12]; new SecureRandom().nextBytes(iv);
    byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, accountId, caseId, iv, token.getBytes(StandardCharsets.UTF_8));
    byte[] stored = Arrays.copyOf(iv, iv.length + encrypted.length);
    System.arraycopy(encrypted, 0, stored, iv.length, encrypted.length);
    return Base64.getEncoder().encodeToString(stored);
  }
  String decrypt(String accountId, String caseId, String stored) {
    byte[] bytes = Base64.getDecoder().decode(stored);
    if (bytes.length < 28) throw new IllegalStateException("Journey case link unavailable");
    return new String(crypt(Cipher.DECRYPT_MODE, accountId, caseId, Arrays.copyOf(bytes, 12),
        Arrays.copyOfRange(bytes, 12, bytes.length)), StandardCharsets.UTF_8);
  }
  private byte[] crypt(int mode, String accountId, String caseId, byte[] iv, byte[] content) {
    if (secret.getBytes(StandardCharsets.UTF_8).length < 32)
      throw new IllegalStateException("Journey case link protection unavailable");
    try {
      byte[] key = MessageDigest.getInstance("SHA-256")
          .digest(("journey-case-link.v1:" + secret).getBytes(StandardCharsets.UTF_8));
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
      cipher.updateAAD((accountId + ":" + caseId).getBytes(StandardCharsets.UTF_8));
      return cipher.doFinal(content);
    } catch (GeneralSecurityException failure) { throw new IllegalStateException("Journey case link protection unavailable"); }
  }
}

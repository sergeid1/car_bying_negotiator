package com.dealanalyzer.account;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Contact details are encrypted at rest with the same AES-GCM scheme as case exports and documents,
 * under a key derived for this purpose and bound to the account ID.
 */
@Component
class ContactProtection {
  private final String secret;
  private final ObjectMapper json;
  private final SecureRandom random = new SecureRandom();

  ContactProtection(@Value("${document.storage.encryption-key:}") String secret, ObjectMapper json) {
    this.secret = secret; this.json = json;
  }

  String encrypt(String accountId, Contact contact) {
    try {
      byte[] iv = new byte[12]; random.nextBytes(iv);
      byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, accountId, iv, json.writeValueAsBytes(contact));
      byte[] stored = Arrays.copyOf(iv, iv.length + encrypted.length);
      System.arraycopy(encrypted, 0, stored, iv.length, encrypted.length);
      return Base64.getEncoder().encodeToString(stored);
    } catch (java.io.IOException failure) { throw unavailable(failure); }
  }

  Contact decrypt(String accountId, String stored) {
    byte[] bytes = Base64.getDecoder().decode(stored);
    if (bytes.length < 28) throw unavailable(null);
    try {
      return json.readValue(crypt(Cipher.DECRYPT_MODE, accountId, Arrays.copyOf(bytes, 12),
          Arrays.copyOfRange(bytes, 12, bytes.length)), Contact.class);
    } catch (java.io.IOException failure) { throw unavailable(failure); }
  }

  private byte[] crypt(int mode, String accountId, byte[] iv, byte[] content) {
    if (secret.getBytes(StandardCharsets.UTF_8).length < 32) throw unavailable(null);
    try {
      byte[] key = MessageDigest.getInstance("SHA-256")
          .digest(("buyer-account-contact.v1:" + secret).getBytes(StandardCharsets.UTF_8));
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
      cipher.updateAAD(accountId.getBytes(StandardCharsets.UTF_8));
      return cipher.doFinal(content);
    } catch (GeneralSecurityException failure) { throw unavailable(failure); }
  }

  private AccountProblem unavailable(Exception cause) {
    // The cause is deliberately dropped: it could carry key or contact material into a log.
    return new AccountProblem(HttpStatus.SERVICE_UNAVAILABLE, "ACCOUNTS_UNAVAILABLE",
        "Buyer Accounts are unavailable right now. Your Buying Case still works without one.");
  }
}

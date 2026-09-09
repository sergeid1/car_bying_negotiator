package com.dealanalyzer.buyingcase.persistence;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
/** Archives may contain document originals, so persisted downloads retain encryption at rest. */
@Component
class ExportContentProtection {
  private final String secret;
  ExportContentProtection(@Value("${document.storage.encryption-key:}") String secret) { this.secret=secret; }
  String encrypt(String id, byte[] content) {
    byte[] iv=new byte[12]; new SecureRandom().nextBytes(iv);
    byte[] encrypted=crypt(Cipher.ENCRYPT_MODE,id,iv,content);
    byte[] stored=Arrays.copyOf(iv,iv.length+encrypted.length);
    System.arraycopy(encrypted,0,stored,iv.length,encrypted.length);
    return Base64.getEncoder().encodeToString(stored);
  }
  byte[] decrypt(String id,String stored) {
    byte[] bytes=Base64.getDecoder().decode(stored);
    if(bytes.length<28) throw new IllegalStateException("Export unavailable");
    return crypt(Cipher.DECRYPT_MODE,id,Arrays.copyOf(bytes,12),Arrays.copyOfRange(bytes,12,bytes.length));
  }
  private byte[] crypt(int mode,String id,byte[] iv,byte[] content) {
    if(secret.getBytes(StandardCharsets.UTF_8).length<32) throw new IllegalStateException("DOCUMENT_STORAGE_ENCRYPTION_KEY must contain at least 32 bytes");
    try {
      byte[] key=MessageDigest.getInstance("SHA-256").digest(("buying-case-export.v1:"+secret).getBytes(StandardCharsets.UTF_8));
      var cipher=Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
      cipher.updateAAD(id.getBytes(StandardCharsets.UTF_8)); return cipher.doFinal(content);
    } catch(GeneralSecurityException failure) { throw new IllegalStateException("Export protection unavailable",failure); }
  }
}

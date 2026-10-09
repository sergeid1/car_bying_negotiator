package com.dealanalyzer.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.upokecenter.cbor.CBORObject;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.*;

/** Test-only CTAP2 authenticator: owns its private key and signs actual WebAuthn bytes. */
final class SoftwarePasskey {
  private static final ObjectMapper JSON = new ObjectMapper();
  final KeyPair key;
  final byte[] id = new byte[32];
  String userHandle;
  SoftwarePasskey() throws Exception {
    var generator = KeyPairGenerator.getInstance("EC"); generator.initialize(new ECGenParameterSpec("secp256r1"));
    key = generator.generateKeyPair(); new SecureRandom().nextBytes(id);
  }
  String registration(JsonNode options, String origin) throws Exception {
    userHandle = options.at("/publicKey/user/id").asText();
    byte[] client = client(options, origin, "webauthn.create");
    var publicKey = (ECPublicKey) key.getPublic();
    var cose = CBORObject.NewMap().Add(1, 2).Add(3, -7).Add(-1, 1)
        .Add(-2, coordinate(publicKey.getW().getAffineX().toByteArray()))
        .Add(-3, coordinate(publicKey.getW().getAffineY().toByteArray())).EncodeToBytes();
    byte[] prefix = authData(options.at("/publicKey/rp/id").asText(), 0x5d, 0); // UP, UV, BE, BS, AT
    byte[] auth = ByteBuffer.allocate(prefix.length + 16 + 2 + id.length + cose.length).put(prefix)
        .put(new byte[16]).putShort((short) id.length).put(id).put(cose).array();
    byte[] attestation = CBORObject.NewMap().Add("fmt", "none").Add("attStmt", CBORObject.NewMap())
        .Add("authData", auth).EncodeToBytes();
    return credential(Map.of("clientDataJSON", b64(client), "attestationObject", b64(attestation), "transports", List.of("internal")));
  }
  String assertion(JsonNode options, String origin, long count) throws Exception {
    return assertion(options, origin, count, userHandle, 0x1d);
  }
  String assertion(JsonNode options, String origin, long count, String handle, int flags) throws Exception {
    byte[] client = client(options, origin, "webauthn.get");
    byte[] auth = authData(options.at("/publicKey/rpId").asText(), flags, count);
    var signature = Signature.getInstance("SHA256withECDSA"); signature.initSign(key.getPrivate());
    signature.update(auth); signature.update(MessageDigest.getInstance("SHA-256").digest(client));
    return credential(Map.of("clientDataJSON", b64(client), "authenticatorData", b64(auth), "signature", b64(signature.sign()), "userHandle", handle));
  }
  private String credential(Map<String, Object> response) throws Exception {
    return JSON.writeValueAsString(Map.of("id", b64(id), "rawId", b64(id), "type", "public-key", "response", response,
        "clientExtensionResults", Map.of()));
  }
  private byte[] client(JsonNode options, String origin, String type) throws Exception {
    return JSON.writeValueAsBytes(Map.of("type", type, "challenge", options.at("/publicKey/challenge").asText(), "origin", origin, "crossOrigin", false));
  }
  private byte[] authData(String rp, int flags, long count) throws Exception {
    return ByteBuffer.allocate(37).put(MessageDigest.getInstance("SHA-256").digest(rp.getBytes(StandardCharsets.UTF_8)))
        .put((byte) flags).putInt((int) count).array();
  }
  private static byte[] coordinate(byte[] value) {
    byte[] result = new byte[32]; int length = Math.min(32, value.length);
    System.arraycopy(value, value.length - length, result, 32 - length, length); return result;
  }
  static String b64(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
}

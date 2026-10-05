# 80 — Make document encryption keys rotatable

**What to build:** An operator can replace the document storage key without losing access to stored documents. `EncryptedFileDocumentStorage` derives one AES key from `DOCUMENT_STORAGE_ENCRYPTION_KEY` by hashing the configured string. It records a key reference with each object but never reads it back, so decryption always uses the current key. Changing the key, after a suspected leak or on a schedule, makes every stored original unreadable. Found in the 2026-10-05 codebase security review.

**Blocked by:** none. Ticket 75 moves the missing-key check to startup; whichever merges second keeps that behaviour.

**Status:** ready-for-agent — filed on 2026-10-05 from a codebase security review.

- [ ] The service accepts one active key and any number of retired keys, each with a stable identifier. New objects are encrypted with the active key, and each object records the identifier of the key that encrypted it.
- [ ] Decryption selects the key by the object's recorded identifier. An object whose key is no longer configured fails closed with a stated error, and the Buyer sees the existing document-unavailable state rather than a server error.
- [ ] Configured key material must be at least 32 random bytes in a stated encoding; a passphrase-like value is refused under pilot or production. Existing development objects encrypted under the current scheme stay readable.
- [ ] An operator command or startup task re-encrypts objects from retired keys to the active key in bounded, resumable batches, keeping the case-bound authenticated data, and reports progress without logging content or keys.
- [ ] Case export, retention cleanup and deletion work across mixed key generations, proven by acceptance tests.
- [ ] The hardening runbook describes the rotation procedure and when a retired key can be removed.

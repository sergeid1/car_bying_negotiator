# 80 — Make document encryption keys rotatable

**What to build:** An operator can replace the document storage key without losing access to stored documents. `EncryptedFileDocumentStorage` derives one AES key from `DOCUMENT_STORAGE_ENCRYPTION_KEY` by hashing the configured string. It records a key reference with each object but never reads it back, so decryption always uses the current key. Changing the key, after a suspected leak or on a schedule, makes every stored original unreadable. Found in the 2026-10-05 codebase security review.

**Blocked by:** none. Ticket 75 moves the missing-key check to startup; whichever merges second keeps that behaviour.

**Status:** complete — a document key ring in `com.dealanalyzer.document` (no migration), as of 2026-10-05.
`DOCUMENT_STORAGE_ACTIVE_KEY_ID` and `DOCUMENT_STORAGE_KEYS` (`id=base64,…`) configure one active and any number of
retired keys; new originals and normalized pages carry an authenticated header naming their key, and headerless
objects still read with the legacy `DOCUMENT_STORAGE_ENCRYPTION_KEY` derivation. A missing key or unreadable object
is a retryable 503 document-unavailable answer. `DocumentKeyRotation` is an offline, resumable, count-only operator
command documented in the hardening runbook. Open: rotation requires the service stopped and does not take a
distributed lock; relay message content and export snapshots still encrypt with the legacy key and are not rotated;
atomic file moves are now required, so storage that lacks them fails closed.

- [x] The service accepts one active key and any number of retired keys, each with a stable identifier. New objects are encrypted with the active key, and each object records the identifier of the key that encrypted it.
- [x] Decryption selects the key by the object's recorded identifier. An object whose key is no longer configured fails closed with a stated error, and the Buyer sees the existing document-unavailable state rather than a server error.
- [x] Configured key material must be at least 32 random bytes in a stated encoding; a passphrase-like value is refused under pilot or production. Existing development objects encrypted under the current scheme stay readable.
- [x] An operator command or startup task re-encrypts objects from retired keys to the active key in bounded, resumable batches, keeping the case-bound authenticated data, and reports progress without logging content or keys.
- [x] Case export, retention cleanup and deletion work across mixed key generations, proven by acceptance tests.
- [x] The hardening runbook describes the rotation procedure and when a retired key can be removed.

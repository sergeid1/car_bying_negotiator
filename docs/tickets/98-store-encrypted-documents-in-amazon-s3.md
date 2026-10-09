# 98 — Store encrypted documents in Amazon S3

**What to build:** An S3 implementation of `DocumentObjectStorage` so originals and normalized pages live in
a private S3 bucket instead of the server's disk. `EncryptedFileDocumentStorage` already encrypts every
object with the application's AES-GCM key ring before writing it; the S3 adapter stores the same ciphertext,
so S3 only ever holds encrypted bytes and key rotation (ticket 80) keeps working. Chosen by the operator on
2026-10-08 as part of hosting the backend on AWS.

**Blocked by:** None for code, using an S3-compatible test container (for example LocalStack or MinIO).
Live use needs ticket 93's AWS account and ticket 99's bucket.

**Status:** open — agent-ready. Backend ticket; pairs well in parallel with ticket 94.

- [ ] `document.storage.provider=file|s3` selects the adapter; `file` stays the default for development.
      S3 uses the AWS SDK v2 with the default credential chain (an instance role in deployment), a
      configured bucket and region, and no access keys in configuration files.
- [ ] Object keys contain only case and document identifiers already used by the file adapter, never names
      or contact details. Objects are written with server-side encryption (SSE-S3 or SSE-KMS) in addition
      to the application's encryption.
- [ ] Delete removes the object, and erasure, retention and case deletion (tickets 17 and 80) behave the same
      as with the file adapter. The bucket policy documented for ticket 99 has public access blocked and
      versioning off, or noncurrent versions expiring within the deletion policy's window, so a deleted
      document does not survive as an old version. `docs/case-deletion-policy.md` states which applies.
- [ ] A pilot or production deployment refuses the file adapter unless explicitly allowed, and refuses
      an S3 bucket that is missing, public, or has versioning enabled without a noncurrent expiry.
- [ ] The existing storage, export, erasure and key-rotation tests run against both adapters; failures
      (timeouts, missing object, access denied) map to the same Buyer-visible outcomes as the file adapter.
- [ ] `docs/provider-inventory.md` lists Amazon S3 (region, encrypted originals) in place of the
      operator-supplied path, and the hardening runbook covers backup and restore expectations.

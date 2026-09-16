# Ticket 20 threat model

Scope: account-free Buyer API, React client, JPA metadata, local encrypted object storage,
document workers, provider adapters, recovery/export, operations and deletion. A case token
is a bearer credential. A Buying Case is the isolation unit; knowledge of a UUID is not authority.

| Boundary / abuse | Controls and executable evidence | Residual release obligation |
| --- | --- | --- |
| Case link theft, guessing, replay | Token hashes, expiry, generic 404; `EphemeralBuyingCaseAcceptanceTest`; fragment-only recovery link, single-use rotation in `CasePortabilityAcceptanceTest` | HTTPS, ingress redaction and no third-party scripts on credential-bearing pages |
| Tenant / case substitution | Case authorization before object/report access; compound report key; `PersistenceHardeningTest`, ephemeral, portability and operations suites | Re-run every new endpoint with another case's token and object IDs |
| Object-key enumeration | Encrypted storage, case-bound keys, no anonymous object HTTP route; document and portability acceptance suites | Private volume/object ACLs must be exercised on deployed storage |
| Signed URL leakage / expiry | No signed object URLs are issued: downloads proxy through authenticated, expiring exports | Any future signed URL implementation needs TTL, audience and revocation tests before use |
| Upload MIME confusion, executables, malware | Signatures, file/page/pixel/expansion limits, fail-closed scanner; `DealerDocumentAcceptanceTest`, `ClamAvMalwareScannerTest` | Production ClamAV service and signature freshness must be measured; the test scanner is forbidden for real uploads |
| Parser CPU, memory and active content | Size/page/pixel checks; HEIC subprocess timeout; scanner failures store nothing; normalization failures remove partial output | PDFBox and PNG/JPEG decoding still execute in the application JVM. The class name `IsolatedDocumentPageNormalizer` does not establish OS isolation. Pilot remains blocked on process/container isolation and adversarial PDF CPU/heap/decompression evidence |
| Worker redelivery / partial writes | Durable operation keys, database locking, terminal completion; `PersistenceHardeningTest`, synthetic restart and document concurrent retry tests | Multiple live worker instances and kill-during-work scenarios require deployment testing |
| Provider outages / prompt injection | Safe failure categories, bounded provider concurrency and deadlines (`ProviderMeterTest`), grounded output, frozen provider contracts; market, extraction, operations and evaluation suites | Provider adapters must set transport deadlines; `ProviderMeter` cancels timed-out calls and discards late results, but cannot forcibly terminate an adapter that ignores interruption. Adapters must not persist case state themselves; an exhausted provider pool fails closed until calls finish or the service restarts |
| Support overreach | Disabled-by-default named accounts, assigned field scope and current consent; `ServiceOperationsAcceptanceTest` | Populate named owner/on-call roster; exercise grant and revocation with real operator identities |
| Abuse / resource exhaustion | API peer-address fixed-window limiter, bounded map, 429 with Retry-After, connection/header caps; `SecurityHardeningAcceptanceTest`; deletion batches | Shared ingress quotas across replicas; app limiter is per process and can reset on restart. Proxy peers share a bucket. Startup analysis/document recovery uses ID cursors and batches of at most 100, with one executor task per workflow; multi-replica ownership and normal-request executor admission still need deployment load testing |
| Deletion and backup resurrection | Immediate access revocation, verified graph/artifact removal, retry receipts; restore-mode API fence and independent UUID ledger; `BackupRestoreAcceptanceTest` | Latest ledger durability/freshness, backup rotation, provider erasure, production RPO/RTO and physical backup expiry remain unverified |
| Dependency / build compromise | Patched managed dependencies, Trivy filesystem/image gates, repository-owned Semgrep rules, npm audit | Protect CI settings, pin promoted image digest, scan each candidate and retain reviewed findings; source rules are a narrow guardrail, not a full taint audit |
| UI exclusion or misleading status | Labels, non-color statuses, keyboard skip link, visible focus; axe AA-tagged rules and keyboard test | Real-browser contrast/reflow, complete keyboard path and VoiceOver/NVDA walkthrough remain required |

The API rate-limit defaults are 600 requests per minute per network peer, at most 10,000
peer buckets per process. Limits are configurable with `security.rate-limit.requests`,
`security.rate-limit.window`, and `security.rate-limit.max-clients`; nonpositive values fail
startup. `server.forward-headers-strategy=none` prevents client headers from changing the peer.
The edge must apply lower write/upload/recovery quotas and a global cap before forwarding traffic.
The service deliberately returns the same limit response for invalid and valid case IDs.

Deletion uses a batch size of 100 (valid range 1–1000). Oldest last-attempt/request time wins,
so a repeatedly failing job does not permanently monopolize the first batch. HTTP transport
caps limit open connections to 200, queued connections to 50 and request threads to 50.

Trust assumptions: the database, encryption keys, application deployment and deletion ledger
are operator-controlled. A compromised application process can access its configured storage
and database; application authorization is not a substitute for process confinement.

Provider calls default to a 30-second deadline and at most eight concurrent invocations,
with no waiting queue (`provider.call.timeout`, positive and at most three minutes;
`provider.call.max-concurrent`, 1–64). Timeout, interrupted callers and exhausted capacity
record `PROVIDER_UNAVAILABLE`. Provider exception text is discarded. Case-state persistence
stays on the caller after a successful result; a late result cannot become a report.
Recovery cursors advance by immutable operation ID rather than offset, so completing one
batch cannot cause the next batch to skip rows. This is startup recovery for a single active
worker deployment, not distributed queue leasing.

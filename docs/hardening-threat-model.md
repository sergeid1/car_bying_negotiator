# Ticket 20 threat model

Scope: account-free Buyer API, React client, JPA metadata, local encrypted object storage,
document workers, provider adapters, recovery/export, operations and deletion. A case token
is a bearer credential. A Buying Case is the isolation unit; knowledge of a UUID is not authority.

| Boundary / abuse | Controls and executable evidence | Residual release obligation |
| --- | --- | --- |
| Case link theft, guessing, replay | Token hashes, expiry, generic 404; `EphemeralBuyingCaseAcceptanceTest`; fragment-only recovery link, single-use rotation in `CasePortabilityAcceptanceTest` | HTTPS, ingress redaction and no third-party scripts on credential-bearing pages |
| Tenant / case substitution | Case authorization before object/report access; compound report key; `CaseIsolationInventoryAcceptanceTest` classifies every registered endpoint and calls each case-scoped one with no, a malformed, an expired and another case's token and with another case's object IDs, requiring the same response as an unknown case or object; `PersistenceHardeningTest`, ephemeral, portability and operations suites | Classify each new endpoint in `CaseIsolationInventoryAcceptanceTest`, which fails until it is listed, and give it fixture objects of its own kind |
| Object-key enumeration | Encrypted storage, case-bound keys, no anonymous object HTTP route; document and portability acceptance suites | Private volume/object ACLs must be exercised on deployed storage |
| Signed URL leakage / expiry | No signed object URLs are issued: downloads proxy through authenticated, expiring exports | Any future signed URL implementation needs TTL, audience and revocation tests before use |
| Upload MIME confusion, executables, malware | Signatures, file/page/pixel/expansion limits, fail-closed scanner; shared deployment decision refuses `document.malware-scanner=test` and missing/short storage keys at pilot/production startup; `DealerDocumentAcceptanceTest`, `ClamAvMalwareScannerTest`, `DeploymentStageGuardTest` | Production ClamAV service and signature freshness must be measured; the test scanner is forbidden for real uploads |
| Parser CPU, memory and active content | Disposable parser JVM for inspection/rendering/text, bounded heap/time/concurrency/IPC, incremental PDF expansion limits; `DocumentParserProcessTest`, `DeploymentStageGuardTest` and packaged-JAR integration test; partial output cleanup | Every pilot/production declaration requires per-request containers with filesystem/network isolation and native resource quotas; local process mode remains development-only. Exercise daemon/host crash recovery on the chosen deployment; see [parser policy](document-parser-policy.md) |
| Worker redelivery / partial writes | Durable operation keys, database locking, terminal completion; `PersistenceHardeningTest`, synthetic restart and document concurrent retry tests | Multiple live worker instances and kill-during-work scenarios require deployment testing |
| Provider outages / prompt injection | Safe failure categories, bounded provider concurrency and deadlines (`ProviderMeterTest`), grounded output, frozen provider contracts; market, extraction, operations and evaluation suites | Provider adapters must set transport deadlines; `ProviderMeter` cancels timed-out calls and discards late results, but cannot forcibly terminate an adapter that ignores interruption. Adapters must not persist case state themselves; an exhausted provider pool fails closed until calls finish or the service restarts |
| Support overreach | Disabled-by-default named accounts, assigned field scope and current consent; `ServiceOperationsAcceptanceTest` | Sergei is the sole operator; exercise grant/revocation and delivery to his alert destination |
| Abuse / resource exhaustion | API peer-address fixed-window limiter, bounded map, 429 with Retry-After, connection/header caps; sign-in and both case recovery email routes share a stricter peer quota and an hourly delivery ceiling with unchanged acknowledgements and one address-free warning per exhausted window; `SecurityHardeningAcceptanceTest`, `RequestEmailLimitAcceptanceTest`, `RequestEmailLimitsTest`; deletion batches | Shared ingress quotas across replicas; app limiters are per process and reset on restart. Proxy peers share a bucket. Route the hourly email warning to the operator's monitored destination. Startup analysis/document recovery uses ID cursors and batches of at most 100, with one executor task per workflow; multi-replica ownership and normal-request executor admission still need deployment load testing |
| Deletion and backup resurrection | Immediate access revocation, verified graph/artifact removal, retry receipts; restore-mode API fence and independent UUID ledger; `BackupRestoreAcceptanceTest` | Latest ledger durability/freshness, backup rotation, provider erasure, production RPO/RTO and physical backup expiry remain unverified |
| Dependency / build compromise | Patched managed dependencies, Trivy filesystem/image gates, repository-owned Semgrep rules, npm audit | Protect CI settings, pin promoted image digest, scan each candidate and retain reviewed findings; source rules are a narrow guardrail, not a full taint audit |
| UI exclusion or misleading status | Labels, non-color statuses, keyboard skip link, visible focus; axe AA-tagged rules and keyboard test | Real-browser contrast/reflow, complete keyboard path and VoiceOver/NVDA walkthrough remain required |

The API rate-limit defaults are 600 requests per minute per network peer, at most 10,000
peer buckets per process. Limits are configurable with `security.rate-limit.requests`,
`security.rate-limit.window`, and `security.rate-limit.max-clients`; nonpositive values fail
startup. `server.forward-headers-strategy=none` prevents client headers from changing the peer.
Sign-in requests, case recovery enrollment and public recovery requests share a default quota
of five requests per peer per 15 minutes (`security.request-email.peer-requests`,
`security.request-email.peer-window`), with at most 10,000 peer buckets
(`security.request-email.max-peers`). They use the same network peer as the API limiter,
never forwarding headers. Peer exhaustion returns the same generic 429 and `Retry-After`.
Their combined delivery attempts are capped at 100 per hour
(`security.request-email.hourly-limit`); exhausted requests keep the original acknowledgement
and send no mail or link. The first suppressed delivery logs an address-free
`REQUEST_EMAIL_HOURLY_LIMIT_REACHED` warning, once per window. All limits must be positive at
startup. Failed delivery attempts consume capacity; idempotent replays and requests with no
eligible delivery do not. Windows begin with the first request/attempt and use monotonic time.
The edge must still apply shared write/upload/email quotas and global caps across replicas
before forwarding traffic, and protect against restart resets and distributed clients.
The service deliberately returns the same limit response for invalid and valid case IDs.

Declare pilot/production with `SPRING_PROFILES_ACTIVE=pilot` or `production`, or equivalently
`EVIDENCE_DEPLOYMENT=pilot` or `production`. The shared `DeploymentStage` decision recognizes
`pilot`, `production` and `prod` case-insensitively with surrounding whitespace trimmed.
`checkout.deployment` and `inventory.deployment` are restrictive legacy aliases; development
settings cannot override any production-like declaration. Every development-only guard uses
this decision. Document storage supports an active/retired base64 random key ring and authenticated
per-object key IDs; pilot/production refuses passphrase-like material at startup. Legacy
development derivation stays readable. See the [rotation procedure](hardening-runbook.md).

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

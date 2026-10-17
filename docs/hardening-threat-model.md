# Buyer service threat model

Optional passwords (ticket 96) introduce credential-stuffing and online guessing risk. Account
creation/recovery remains email-link based. Passwords use random-salted bcrypt cost 12, minimum
12 code points and maximum 72 UTF-8 bytes without composition rules. Breached-password screening
sends only a five-character SHA-1 prefix to Pwned Passwords with response padding; outages permit
changes and emit a fixed warning. Unknown/password-free/wrong-password accounts do the same
bcrypt work and receive identical credential-failure details. Keyed email/client counters apply
exponential backoff alongside the API peer limit, without trusting forwarding headers. Counters
are bounded and fail closed at capacity; restart and multiple replicas require shared ingress
quotas. Throttled attempts skip expensive hashing uniformly. This limits timing disclosure but
does not claim constant-time network/database behavior. Email-link creation remains observable
to the address owner through delivery; it never provides a public account-existence response.

Password mutations require a sign-in within 10 minutes, send mandatory account-security email,
record credential-free security events and revoke all other sessions. Email recovery resets the
password through the same mutation; hashes never leave storage and disappear with account deletion.
Session methods are recorded: password-only sessions cannot connect or replace a mailbox or add
passkeys that would bypass this restriction. Starting mailbox authorization requires a fresh
email-link/passkey session. Completion checks the method but not session age because state is
account-bound and consent may finish after the sign-in window; state retains its own expiry.
Any active session can disconnect a mailbox to withdraw send permission. A stolen password still grants access to account
paperwork and existing permitted actions; a compromised email inbox or authenticator remains
an account takeover boundary. Provider-outage fail-open and per-process throttles are explicit
operational risks, not proof that credential stuffing is impossible.

Passkeys (ticket 95): a maintained Yubico verifier checks exact configured origin/RP ID,
challenge, user presence/verification, signature and credential/user-handle ownership. Pilot
and production refuse localhost configuration through the shared DeploymentStage decision.
Discoverable sign-in takes no address and exposes no account existence. Registration requires
an active account session with the request header and binds its attempt to that exact session.
Five-minute persisted attempts are atomically single-use even on failed verification. Account
locks serialize credential counters with removal and deletion. Signed counter regressions are
refused and recorded as account-security events; backup eligibility/state are retained. A
counterless or synced authenticator is not proof of unique physical possession. Biometric data
and private keys remain in the authenticator. Compromised browser scripts or an existing stolen
session can still add a credential; additions send mandatory ACCOUNT_SECURITY email. Email-link
recovery remains an account takeover boundary. Metadata-only export excludes key material and
credential IDs; deletion verifies removal of credentials, attempts and security events. The
client CSP stays same-origin and Permissions-Policy confines WebAuthn to self. Existing API
peer limits bound public requests; ingress must enforce shared quotas across replicas.

Scope (reviewed 2026-10-07): the account-free Buyer API and optional Buyer Accounts,
session cookie and account case pass; React client; JPA metadata and encrypted object
storage; document workers; recovery/export, operations and deletion; Buying Journeys,
negotiation drafts and mandates; appointments and dealer-specific contact release;
Buyer notification email; and the model execution harness. A case link is a bearer
credential. An account case pass requires the issuing session cookie as well. A Buying
Case is the case-data isolation unit; knowledge of a UUID is not authority.

There are two dealer send paths: exact approved text handed to the Buyer's own email
app, and a Buyer-triggered send through a Mailbox Connection. Connections use OAuth
grants, send-only mailbox permissions plus identity scopes, encrypted access/refresh
tokens and disconnect. The platform relay is **inbound-only**: each case has a private
"Forward a dealer's email here" address; forwarding is unavailable without a real
authenticated inbound adapter. Historical `RELAY` send records confer no send authority.
The configured model default is Anthropic Opus 5.5 (`claude-opus-5-5`); model calls are
disabled until `ModelRolloutGate` admits the selected provider and exact task versions.

Trust boundaries:

Ticket 99 selects Cloudflare Workers and AWS but provisions nothing. Cloudflare terminates
TLS and sees API uploads in transit at its global edge; AWS regional encryption does not
limit that edge processing to the AWS region. The Worker overwrites an origin-secret header,
and the EC2 Nginx ingress requires it in addition to Cloudflare-only TCP443 security-group
rules. Spring and ClamAV listen on loopback. Local Worker tests and `deploy/pilot/test_ingress.py`
prove the exercised routing/header/refusal behavior with synthetic fixtures. Actual origin TLS,
network isolation, upload limits, scanner freshness, rootless parser quotas, deletion and
backup expiry remain deployment obligations in [pilot deployment](pilot-deployment.md).
The dedicated rootless parser socket is a trusted launcher capability: a compromised app can
control that user's daemon; it must never be a privileged host socket or own other workloads.

- **Buyer-controlled:** browser, case-link possession, sign-in mailbox access, shopping
  inputs, pasted/uploaded replies, mandates, exact-text approval, appointment choices,
  contact-release fields and OAuth consent. In the own-email-app path the Buyer controls
  the actual recipient, edits and delivery; "I sent it" is a Buyer statement, not provider
  evidence. A connected mailbox necessarily exposes its sending address to the dealer.
- **Dealer-controlled:** inbound sender/subject/body, quoted forwarding headers,
  attachments, listing prose and offers. These are untrusted even when a provider
  authenticates transport. A quoted `From:` line does not authenticate a dealer.
- **Operator-controlled:** deployment, ingress/static-host headers, database, object
  storage, keys, deletion ledger, provider selection and credentials, OAuth redirect
  registration, notification journey URL, task instructions/schemas, rollout records
  and approved provider terms. Application compromise exposes configured secrets and
  data; authorization and encryption do not confine a compromised process.
- **Provider-controlled:** sign-in/notification transport sees recipients and links;
  the inbound mail provider sees forwarding addresses and content; Google/Microsoft
  receive OAuth exchanges and approved dealer messages on the connected-mailbox path;
  the selected model provider receives submitted document text/images, dealer responses
  and task facts when enabled. Provider retention, grant withdrawal, delivery outcomes
  and vendor-side processing need evidence beyond local tests.

Evidence below names executable test classes (frontend evidence names its test/spec file).
Tests with fake adapters or stub servers prove the exercised local boundary, not live
provider authentication, delivery, erasure or arbitrary prompt-injection resistance.
Untested behavior belongs in the residual column, even when code intends to enforce it.
Related policies: [Buyer Accounts](buyer-account-policy.md), [outbound gate](outbound-gate.md),
[provider inventory](provider-inventory.md), [model harness](ai-execution-harness.md).

| Boundary / abuse | Controls and executable evidence | Residual release obligation |
| --- | --- | --- |
| Case link theft, guessing, replay | Expiry and generic 404 for missing, wrong, foreign and expired credentials: `EphemeralBuyingCaseAcceptanceTest`; fragment recovery link and single-use rotation across restart: `CasePortabilityAcceptanceTest`; built-client same-origin CSP, framing refusal, no-referrer and HSTS header map exercised in `frontend/e2e/client-content-security-policy.spec.ts`. Images alone permit local `blob:` URLs for authenticated normalized-page previews | Apply the client header map at the HTTPS static host, preserve existing API/export headers, verify edge headers and ingress redaction; CSP cannot protect against a compromised same-origin script. A stolen active case link still grants access. See the client-header procedure in the hardening runbook |
| Account takeover / sign-in link theft | Passwordless fragment links, identical new/known-address acknowledgement, single-use verification, expired-link rejection, per-address active-link cap and current/all-session sign-out: `BuyerAccountAcceptanceTest`; shared email quotas: `RequestEmailLimitAcceptanceTest`, `RequestEmailLimitsTest` | An unused stolen link can sign in; mailbox ownership and endpoint/device security are assumptions. Verify HTTPS link origin, mail transport and redaction at ingress and provider; sign-in links and sessions are scoped HMACs in code, but database-only theft resistance is not directly asserted by this acceptance class |
| Session cookie theft / cross-site requests | Cookie is HttpOnly, Secure, SameSite=Strict, scoped to `/api/v1`; no token in sign-in JSON, legacy session header ignored, POST/PUT/DELETE require `Account-Request: 1`, form request refused: `BuyerAccountAcceptanceTest`; insecure-cookie switch refused at pilot/production: `AccountSessionCookieTest` | Verify browser cookie behavior and HTTPS termination in the deployment. The public request marker is not a secret; compromised same-origin scripts can act with the cookie. A stolen active cookie can impersonate the Buyer until expiry/revocation |
| Account case pass theft / replay after sign-out, detachment or case-link rotation | Opening returns an `acct.` pass without revealing the case link; missing/foreign cookie, another session of the same account, foreign/same-account case substitution, forged/altered/malformed fields and out-of-range expiry fail on reads and writes with the same 404 as an unknown case; attachment removal, verified recovery link rotation and the pass's own expiry end access while the session/case remain active; a fresh pass and rotated case link work; sign-out, session expiry, case deletion and account deletion end access: `AccountCaseOpeningAcceptanceTest` ([ticket 91](tickets/91-prove-account-case-pass-revocation-boundaries.md)) | MAC binds case, current case-link hash, expiry and session in `CaseAccessService`; attachment is checked by `AccountCasePassAdmission` and `BuyerAccountService`. Detachment evidence removes the attachment at the persistence lifecycle seam; no public detach feature is provided. Protect cookie and pass together; pass storage in browser memory does not mitigate compromised scripts |
| Tenant / case substitution | `CaseIsolationInventoryAcceptanceTest` classifies every registered endpoint and calls each case-scoped one with no, a malformed, an expired and another case's token and with another case's object IDs, requiring the same response as an unknown case or object; case-bound report lookup: `PersistenceHardeningTest`; account case opening: `AccountCaseOpeningAcceptanceTest` | Classify each new endpoint in `CaseIsolationInventoryAcceptanceTest`, which fails until it is listed, and give it fixture objects of its own kind |
| Object-key enumeration | Wrong-case document access refused and encrypted artifacts inspected: `DealerDocumentAcceptanceTest`; authenticated export access: `CasePortabilityAcceptanceTest`; registered case endpoint authorization: `CaseIsolationInventoryAcceptanceTest` | Private volume/object ACLs must be exercised on deployed storage |
| Signed URL leakage / expiry | Exports proxy through authenticated downloads and expired exports cannot be downloaded: `CasePortabilityAcceptanceTest` | No signed object URLs are issued by the current implementation. Any future signed URL implementation needs TTL, audience and revocation tests before use |
| Upload MIME confusion, executables, malware | Signatures, file/page/pixel/expansion limits, fail-closed scanner; shared deployment decision refuses `document.malware-scanner=test` and missing/short storage keys at pilot/production startup; `DealerDocumentAcceptanceTest`, `ClamAvMalwareScannerTest`, `DeploymentStageGuardTest` | Production ClamAV service and signature freshness must be measured; the test scanner is forbidden for real uploads |
| Parser CPU, memory and active content | Child heap exhaustion contained, timeout kills native descendant, saturation refuses another document and worker receives no application environment: `DocumentParserProcessTest`; file/page/pixel/expansion limits and partial artifact retry: `DealerDocumentAcceptanceTest`; development parser mode refused for production-like declarations: `DeploymentStageGuardTest` | Every pilot/production declaration requires per-request containers with filesystem/network isolation and native resource quotas; local process mode remains development-only. Exercise daemon/host crash recovery on the chosen deployment; see [parser policy](document-parser-policy.md) |
| Worker redelivery / partial writes | Database locking, idempotent operation races, rollback and terminal completion: `PersistenceHardeningTest`; concurrent document retry dispatches once: `DealerDocumentAcceptanceTest` | Multiple live worker instances and kill-during-work scenarios require deployment testing |
| Provider outages | Bounded provider concurrency/deadlines and safe failure categories: `ProviderMeterTest`; safe diagnostics without provider payloads: `ServiceOperationsAcceptanceTest`; schema-checked model failure: `ModelHarnessTest` | Provider adapters must set transport deadlines; `ProviderMeter` cancels timed-out calls and discards late results, but cannot forcibly terminate an adapter that ignores interruption. Adapters must not persist case state themselves; an exhausted provider pool fails closed until calls finish or the service restarts |
| OAuth callback forgery / scopes beyond sending | Forged, foreign-account, consumed and expired state refused; read grant refused; disconnect and newer authorization fence exchanges: `ConnectedMailboxAcceptanceTest`; scope allowlist: `MailboxBoundaryTest`; real adapters against loopback HTTP assert code/PKCE/exact redirect/nonce, provider-specific issuer/client audience/azp/expiry, and malformed reply refusal: `MailboxProviderContractTest` | Signature trust is limited to direct hard-coded HTTPS token-endpoint delivery with JDK TLS validation and no redirects, per OIDC Core; see [mailbox provider boundaries](mailbox-provider-boundaries.md). Stub contracts do not prove deployed app/publisher verification, registered HTTPS redirects or real least-privilege consent. Keep production connecting disabled until those facts are recorded |
| Mailbox token theft at rest | Encrypted tokens/address absent from database plaintext; tokens absent from API/export/logs: `ConnectedMailboxAcceptanceTest`; row/purpose-bound encryption and dedicated minimum key length: `MailboxBoundaryTest`; fake refusal, live missing/short-key refusal and disabled configuration across every production-like declaration: `DeploymentStageGuardTest` | Keep `MAILBOX_TOKEN_ENCRYPTION_KEY` private and independent; record secret-manager access, rotation, backup protection and retirement before enablement. The guard is a configuration check, not app verification. Encryption does not stop an application process from using grants |
| Mailbox access surviving disconnect / replacement / account deletion | Latch-controlled exchange, refresh and dispatch fences, both replacement directions, slow calls and persisted restart: `ConnectedMailboxAcceptanceTest`; invalid_grant and authentication failure at real adapters against HTTP stubs: `MailboxProviderContractTest` | Provider revocation is best effort; local deletion is authoritative. Dispatch locks account, case and exact connection through provider outcome persistence. A committed fence prevents dispatch; an entered call cannot be recalled. Already delivered email cannot be recalled. Record real provider withdrawal/disconnect/deletion and deployed crash drills before enabling live connecting |
| Dealer impersonation / spoofed or forwarded inbound mail | Case-specific private address, unknown address refused, sequential provider-message deduplication, UNTRUSTED storage, immediate intake refusal after case/account deletion: `DealerRelayAcceptanceTest`; fake relay refused for pilot/production and no outbound relay API: `DealerRelayBoundaryTest` | `RelayProductionGuard` permits the unavailable adapter; no real inbound adapter exists. Before enabling one, require verified webhook signature or equivalent, freshness/replay protection, authenticated envelope recipient bound to the live case, and concurrency/cross-case deduplication tests: [ticket 89](tickets/89-authenticate-real-inbound-dealer-mail.md). Provider authentication is not dealer identity. `conversationDealer` associates a forward only when exactly one known dealer appears in quoted From lines; these lines are forgeable and never evidence of authorship |
| Hostile dealer content reaching extraction, drafts or Buyer view | Inbound size ceiling, malware body refusal, attachment intake/scanning and retained content encrypted: `DealerRelayAcceptanceTest`; malformed/unsupported documents and expansion limits: `DealerDocumentAcceptanceTest`; dealer instructions do not send messages or change counter amounts: `NegotiationConversationAcceptanceTest`; reply input bound and generated claims/limits checked: `ReplyAssistantTest`; fixed instructions and untrusted framing in both vendor requests: `AnthropicModelGatewayTest`, `OpenAiModelGatewayTest`; off-schema output refused: `ModelHarnessTest`; relay body HTML shown literally without creating an element: `frontend/src/DealerRelay.test.tsx` | Test arbitrary hostile content on the deployed browser and permissioned model corpus; framing and schema validation do not establish semantic accuracy. Extend the component HTML check to executable script/event payloads across conversation/appointment views in [ticket 89](tickets/89-authenticate-real-inbound-dealer-mail.md). Enforce raw webhook/MIME limits before parsing, null/malformed payload rejection and attachment resource limits at the provider boundary; document sandbox obligations above also apply |
| Buyer identity leaking into dealer-bound drafts | Known encrypted account contact values feed the gate: `AccountOutboundPrivacyTest`; personal values/unknown prose blocked: `OutboundGateTest`; handoff blocks unreleased email, phone and surname in subject/body and header injection: `DealerRelayAcceptanceTest`, `DealerRelayBoundaryTest` | Retained rejected drafts can contain Buyer-entered personal data; protect exports and retention. A sender address and the Buyer's own mail-app signature are visible outside draft screening. Exercise obfuscated/novel identity forms; tests show specific examples, not universal detection |
| Contact release beyond Buyer approval / unintended appointment booking | Anonymous/foreign account cannot book; chosen first name/email released with phone/surname withheld, field/dealer audit without values, no booking from dealer reply alone, reschedule with empty release withholds details: `TestDriveAcceptanceTest`; release withdrawal after a claimed send with a prepared token prevents dispatch, with redacted refusal: `ConnectedMailboxAcceptanceTest` | Dispatch rechecks current release under the case lock; changes after the provider call begins cannot recall that call. Own-email-app handoff already exposes approved contact values to the Buyer before "I sent it"; the service cannot revoke copied text or compel dealer erasure |
| Notification misdelivery / disclosure / duplicate delivery | Verified account recipient, authenticated preferences, opt-out and deletion checks, deduplication, separate security quota and failed/uncertain attempts not retried: `BuyerNotificationsAcceptanceTest`; SMTP adapter uses fixed prompt and journey link only: `NotificationEmailTest`; appointment notice contains no case token/VIN: `TestDriveAcceptanceTest` | Verify production SMTP credentials, recipient routing, journey URL, TLS, domain authentication and delivery monitoring. SENT means adapter acceptance, not inbox receipt; provider/mailbox compromise can expose prompts. No notification-body data minimization claim extends to sign-in/recovery email, which carries a credential |
| Buyer data sent to model provider / unintended processor | Disabled paths make zero calls and schema/model mismatch fails closed: `ModelHarnessTest`; provider-specific terms, credentials, pricing, frozen corpus and exact task comparison gate startup; previous Opus 5 comparison cannot admit Opus 5.5: `ModelRolloutGateTest`; shipped model-off configuration and deterministic extraction: `ModelReleaseCheckTest`; default Opus 5.5 and no cross-provider retry/fallback: `ModelProviderSelectionTest`; OpenAI no-storage request: `OpenAiModelGatewayTest` | Before enablement execute vendor retention/training-exclusion terms, provision credentials, freeze the permissioned corpus and run held-out comparisons for each task/version (tickets 28/36 and [model harness policy](ai-execution-harness.md)). OpenAI `store:false` is a request flag, not proof of vendor erasure. Raw document text/images and dealer responses may contain personal data; no universal redaction is proven. Verify task payload minimization, provider deletion and deployed telemetry redaction |
| Outbound gate bypass / stale approval | Exact templates, traceable amounts within mandate currency/ceiling and frozen adversarial corpus: `OutboundGateTest`; immutable case-authorized decisions and current mandate use: `OutboundDraftAcceptanceTest`; changed approval text refused, risky edits blocked, pause/stop/expiry cancel drafts: `NegotiationConversationAcceptanceTest`; mandate revocation during a claimed mailbox send prevents provider dispatch: `ConnectedMailboxAcceptanceTest`; appointment exact-text approval: `TestDriveAcceptanceTest` | Every new owner/send operation needs current-context and exact-text tests; saved pass is not a send capability. Dispatch rechecks under the case lock; revocation after provider entry cannot recall that call. The Buyer can change copied mail-app text outside the service |
| Connected-mailbox retry into duplicate | Concurrent requests, expired prepared claims, simulated stale success/refusal, slow entered sends beyond in-flight expiry and reopened persisted claims are fenced with latches/database fixtures; SENT refusal, UNKNOWN without automatic retry, exact-text audited Buyer send-again and caps: `ConnectedMailboxAcceptanceTest`; exact real-adapter payloads and one HTTP dispatch for auth/quota/timeout/lost-connection/5xx: `MailboxProviderContractTest` | Explicit send-again can duplicate a message delivered despite UNKNOWN; no exactly-once delivery claim. Restart evidence interrupts after a committed claim and reopens the database, rather than killing an OS process. Production connecting remains disabled pending [deployment obligations](mailbox-provider-boundaries.md). Own-email-app sends cannot be metered or deduplicated by this service |
| Support overreach | Disabled-by-default named accounts, assigned field scope and current consent; `ServiceOperationsAcceptanceTest` | Sergei is the sole operator; exercise grant/revocation and delivery to his alert destination |
| Abuse / resource exhaustion | API peer quota cannot be evaded with forwarding headers or rotated tokens; 429 with Retry-After: `SecurityHardeningAcceptanceTest`; sign-in and both case recovery email routes share a stricter peer quota and an hourly delivery ceiling with unchanged acknowledgements and one address-free warning per exhausted window: `RequestEmailLimitAcceptanceTest`, `RequestEmailLimitsTest`; bounded deletion batches: `CaseDeletionAcceptanceTest`; cursor-based startup recovery: `PersistenceHardeningTest` | Shared ingress quotas across replicas; app limiters are per process and reset on restart. Behind the Worker and pilot proxy, per-client quotas key on the Worker's `X-Otdog-Client-Address` only when the request carries the origin secret, otherwise on the network peer: `ClientAddressAcceptanceTest`, `ClientAddressTest`, `deploy/cloudflare/worker.test.mjs`, `deploy/pilot/test_ingress.py`. A leaked origin secret lets its holder choose quota keys; IPv6 visitors share a /64, and visitors behind one NAT or carrier-grade NAT share a bucket. Exercise configured connection/header caps under deployed load. Route the hourly email warning to the operator's monitored destination. Startup analysis/document recovery uses ID cursors and batches of at most 100, with one executor task per workflow; multi-replica ownership and normal-request executor admission still need deployment load testing |
| Deletion and backup resurrection | Case graph/artifact removal and retry receipts: `CaseDeletionAcceptanceTest`; account session revocation and graph removal: `BuyerAccountAcceptanceTest`; relay content retention/deletion: `DealerRelayAcceptanceTest`; restore-mode API fence and independent UUID ledger: `BackupRestoreAcceptanceTest` | Latest ledger durability/freshness, backup rotation, provider erasure, production RPO/RTO and physical backup expiry remain unverified; restored account/mailbox grant lifecycle needs deployment verification |
| Dependency / build compromise | No executable test class proves dependency or CI integrity | Run Trivy filesystem/image gates, repository-owned Semgrep rules and npm audit on each candidate; protect CI settings, pin promoted image digest and retain reviewed findings. Source rules are a narrow guardrail, not a full taint audit |
| UI exclusion or misleading status | Labels, axe AA-tagged rules and keyboard skip/focus path: `frontend/src/Accessibility.test.tsx`; claim-language scan: `PilotCopyClaimsTest` | Real-browser contrast/reflow, complete keyboard path and VoiceOver/NVDA walkthrough remain required |

The API rate-limit defaults are 600 requests per minute per network peer, at most 10,000
peer buckets per process. Limits are configurable with `security.rate-limit.requests`,
`security.rate-limit.window`, and `security.rate-limit.max-clients`; nonpositive values fail
startup. `server.forward-headers-strategy=none` prevents client headers from changing the peer.
Every per-client limit (API, request email, password sign-in and the Toto client cap) takes its key
from one resolver, `ClientAddress`. Behind the Cloudflare Worker and the pilot proxy the peer is the
proxy, so a request whose `X-Origin-Verify` matches `security.client-address.origin-secret` is keyed
by `X-Otdog-Client-Address`, which the Worker sets from Cloudflare's `CF-Connecting-IP` after deleting
any client copy. Any other request, an unset secret, or a missing, repeated or malformed address
falls back to the peer. IPv6 keys are the /64 prefix. The key lives only in in-memory quotas.
Sign-in requests, case recovery enrollment and public recovery requests share a default quota
of five requests per peer per 15 minutes (`security.request-email.peer-requests`,
`security.request-email.peer-window`), with at most 10,000 peer buckets
(`security.request-email.max-peers`). They use the same client key as the API limiter,
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

Toto (ticket 120) is a public, credential-free model endpoint, so its threats are cost and
content. Buyer questions and up to six earlier turns go to the configured model provider and
are not stored by otdog: the client keeps the chat in memory, and the server writes no question,
turn or answer text to the database, logs, metrics or error reports; parse failures and refusals
return fixed wording that never echoes input. `provider_usage` keeps counts, tokens, cost, outcome
and provenance only. Abuse is bounded by request limits (question 1–1,000 characters, at most six
turns of 1,500), a per-account daily cap counted from durable usage rows, a per-client-address
daily cap through the in-process peer quota, and a deployment-wide daily spending ceiling. The
ceiling adds known `ModelPricing` cost to a worst-case price for every row without a known cost
(in flight, failed or unpriced) and for every call admitted in this process, so unknown cost can
only overstate spend. Admission is serialized per process; with several replicas, calls admitted
at the same moment on different replicas, before either has written its usage row, can together
exceed the ceiling by a few worst-case calls, and the client-address cap resets on restart and is per replica, so the edge must
still apply shared quotas. Behind the Cloudflare Worker and the pilot nginx proxy, which clear
forwarding headers, every visitor without an account has the proxy's address, so the client cap is
one deployment-wide bucket of ten questions a day until the deployment passes a trusted client
address to the service (ticket 136). Many accounts or rotating addresses reach the spending ceiling, which
makes Toto unavailable for everyone until the next UTC day: availability is traded for bounded
cost. A signed-in Buyer's session cookie only selects their cap; the JSON-only content type keeps
a cross-site form from spending it. Injected instructions in questions or turns are content to the
task (ticket 119), and its code-side checks, not the instructions, are the safety boundary.

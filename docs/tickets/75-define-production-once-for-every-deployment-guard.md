# 75 — Define production once for every deployment guard

**What to build:** One component decides whether the service is running as pilot or production, and every guard that refuses development-only behaviour asks it. Today seven guards (VIN decoder, evidence providers, evidence budget, checkout, dealer relay, inventory search, account session cookie) each copy the same check: a `pilot`, `production` or `prod` Spring profile, or the same value in `evidence.deployment`. Three others react only to the `pilot` profile: confined document parsing (`DocumentParserProcess`), the synthetic intake refusal (`SyntheticIntakeAffordance`) and the pilot readiness startup check (`PilotReadiness`). A deployment configured with `EVIDENCE_DEPLOYMENT=production`, as `docs/provider-inventory.md` describes, would parse untrusted dealer documents in the application process, keep synthetic intake open and skip the readiness check, with no startup error. The test malware scanner (`DeterministicMalwareScanner`) has no refusal at all, although the threat model says it is forbidden for real uploads. Found in the 2026-10-05 codebase security review.

**Blocked by:** none.

**Status:** complete — one `DeploymentStage` decision in `com.dealanalyzer.deployment` used by every
development-only guard (no migration), as of 2026-10-05. Pilot/production is a `pilot`, `production` or `prod`
profile or `EVIDENCE_DEPLOYMENT`; `checkout.deployment` and `inventory.deployment` remain restrictive legacy aliases.
A production declaration now also enforces confined parsing, the synthetic intake refusal and the pilot readiness
record, refuses `document.malware-scanner=test`, and checks the storage key at startup. Open: the decision is a
startup snapshot; `@Profile`-annotated beans are not covered by the source scan (none exist today).

- [x] A single deployment-stage component reads the active profiles and `evidence.deployment` once and answers whether the service is running as pilot or production. Every existing guard uses it, and no guard keeps its own copy of the profile list.
- [x] Confined document parsing, the synthetic intake refusal and the pilot readiness check apply under every pilot or production configuration, not only under the `pilot` profile.
- [x] `document.malware-scanner=test` fails startup under pilot or production, naming the setting in the error.
- [x] A missing or too-short `DOCUMENT_STORAGE_ENCRYPTION_KEY` fails startup under pilot or production, instead of failing on the Buyer's first upload. Development keeps the current behaviour.
- [x] One test starts the service under each production-like configuration (`pilot` profile, `production` profile, `prod` profile, `evidence.deployment=pilot`, `evidence.deployment=production`) and proves every development-only adapter and setting is refused under each of them. A newly added guard that bypasses the shared component fails a test.
- [x] `docs/provider-inventory.md`, `docs/document-parser-policy.md` and the threat model describe one way to declare a pilot or production deployment.

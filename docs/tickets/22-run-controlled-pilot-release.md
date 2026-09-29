# 22 — Run the controlled pilot release

**What to build:** The qualified MVP progresses through moderated use into a gated public pilot with controlled exposure, operational support, measurable outcomes, and explicit stop or rollback conditions.

**Blocked by:** 13 — Compare one outside Financing Scenario; 14 — Review a revised Dealer Offer; 15 — Provide grounded explanations and negotiation drafts; 16 — Recover, export, and download a Buying Case; 17 — Expire and delete the complete Buying Case lifecycle; 19 — Capture privacy-minimized feedback and pilot metrics; 20 — Harden security, resilience, performance, and accessibility; 21 — Assemble and close public-pilot readiness evidence; 26 — Carry the Buyer from a verified offer to the report and back; 27 — Replace development-only market and listing adapters with a licensed source; 28 — Build the permissioned document corpus and publish measured extraction accuracy.

**Status:** deferred — sequenced last, after 26, 27, and 28. Deferred on 2026-09-29 by operator
decision: this is release governance, not deployment work, and it cannot start while its blocking
tickets and six of nine readiness prerequisites remain open. Do not pick it up from the ready queue.

- [ ] Twenty-five to fifty moderated cases complete with documented review of material numeric accuracy, unresolved discrepancies, support needs, privacy, and usability.
- [ ] No critical privacy, security, legal-copy, licensing, accessibility, or review-safety issue remains open before expanding access.
- [ ] The public pilot is gated to the approved jurisdiction, providers, document types, purchase types, volume, and Buyer population.
- [ ] Dashboards expose the preregistered completion, usefulness, product-market-fit, numeric-error, cost, reliability, and safety measures.
- [ ] Support, incident, provider-outage, manual-review, deletion, rollback, and pilot-pause runbooks have named owners and exercised procedures.
- [ ] Expansion toward the 100–300 case pilot occurs only while declared quality and safety thresholds remain satisfied.
- [ ] Pilot results are evaluated against thresholds frozen before outcome review, with deviations and release decisions recorded.
- [ ] Production rollout validates Flyway before application traffic, runs Hibernate in schema-validation mode, monitors ORM query count and latency, and includes rollback criteria for migration, locking, connection-pool, or query-regression failures.

## Deferral record — 2026-09-29

The backlog is not waiting on a deployment. Nothing in tickets 23–37 requires a reachable
environment except phone-based upload testing in ticket 24, so a staging environment is worth
standing up shortly before that ticket rather than now. No staging ticket is filed yet.

### Hosting constraints already established

These follow from the shipped code and policy, and do not need re-deriving:

- **The application must run on a host with its own local container daemon.**
  `docs/document-parser-policy.md` forbids mounting a privileged host Docker socket into the
  application container and states that container mode "currently supports the host-run
  application with a local daemon", so a different boundary "must provide and test an equivalent
  boundary before pilot use". This rules out managed platforms that deny daemon access, and makes
  a plain VM with a rootless daemon owned by the service user the only zero-engineering option.
- **Parsing spends one container per page, not per request.**
  `IsolatedDocumentPageNormalizer` calls the parser once per page, so a 25-page document costs
  roughly 27 container launches against a 20-second per-call deadline and the document-level
  p50 < 60 s / p95 < 180 s budget. Per-request microVM providers lose that budget to boot time.
- **Sizing floor.** Two concurrent parser containers at 1536 MiB, plus the application heap, plus
  ClamAV's resident signature database. 8 GB / 4 vCPU for the pilot; a staging box can fit 4 GB by
  setting `DOCUMENT_PARSER_MAX_CONCURRENT=1` and a lower `DOCUMENT_PARSER_HEAP_MB`.
- **The backend is stateful.** `EncryptedFileDocumentStorage` writes encrypted originals to
  `document.storage.path` on a local filesystem; there is no object-storage adapter. Redeploys must
  preserve that volume, and the backup cutoff must cover the volume and the database together, as
  step 1 of the restore procedure in `hardening-runbook.md` requires.
- **Splitting the frontend onto a separate origin is not free.** The client uses relative
  `/api/v1/...` paths throughout with no configurable API base, and there is no CORS policy. A CDN
  rewrite would also collapse `ApiRateLimitFilter`'s per-client buckets, because it keys on
  `request.getRemoteAddr()` while `server.forward-headers-strategy=none`. Same-origin hosting needs
  no code change; a split origin needs an API base URL, a CORS policy, and a trusted client-identity
  source for rate limiting.
- **A managed database that suspends when idle cannot produce performance evidence.** Cold starts
  invalidate the p95 < 500 ms recalculation measurement, so any latency evidence for this ticket
  must come from an always-on database.

### Still required before this ticket can open

Its blocking tickets 26, 27, and 28, plus the five other blocked prerequisites in
[the readiness record](../pilot-readiness-record.md). Selecting hosting, object storage, queue,
mail, and model providers is what clears `infrastructure-and-privacy-inventory`; that selection is
part of this ticket's scope and remains unmade.

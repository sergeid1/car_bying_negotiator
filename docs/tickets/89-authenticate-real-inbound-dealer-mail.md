# 89 — Authenticate real inbound dealer mail

**What to build:** A tested authenticated boundary before enabling any real inbound provider for
the case's private forwarding address. `RelayService.receive` is currently an internal method;
`RelayProductionGuard` refuses the fake in production-like stages, and no real inbound adapter
exists. Its sequential provider-message lookup is not webhook authentication or proof of
concurrent replay handling. Quoted `From:` lines associate conversation content but are not
dealer identity. Found while updating ticket 78's threat model on 2026-10-07.

**Blocked by:** Provider selection and webhook contract; keep `dealer-relay.provider=none`
for production-like stages until the boundary and deployment evidence are complete.

**Status:** needs-triage — provider selection is unresolved; prerequisite for real inbound intake.

- [ ] Authenticate provider webhook signatures or an equivalent authenticated delivery channel;
  reject tampering, unknown keys and expired delivery timestamps before parsing/storing content.
- [ ] Bind the authenticated envelope recipient to exactly one live case address; reject unknown,
  expired and deletion-pending cases. Do not accept a body/quoted-header case identifier as authority.
- [ ] Test sequential and concurrent replay, cross-case provider-message ID reuse and retry after
  partial intake; define provider namespace and database uniqueness without leaking another case's IDs.
- [ ] Enforce raw request/MIME size, attachment count and parser limits; reject malformed/null
  fields safely, and route accepted attachments through existing malware/document intake.
- [ ] Cover direct and forwarded mail association, ambiguous/spoofed quoted From lines, and preserve
  UNTRUSTED status without claiming dealer authorship or automatically approving offers/actions.
- [ ] Extend the existing `DealerRelay.test.tsx` literal-HTML assertion with browser coverage for
  hostile subjects/bodies/filenames and script/event payloads in relay, conversation and appointment views.
- [ ] Keep real intake disabled until tests and deployed signature/replay/routing checks are recorded
  alongside `RelayProductionGuard`; update the threat model with named executable evidence.

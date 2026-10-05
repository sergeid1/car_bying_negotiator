# 84 — Retire the platform relay and delegated mode

**What to build:** The platform stops sending email to dealers in its own name. Outbound relay sending,
the platform footer, the dealer opt-out, and the mandate's autonomy mode are removed, so the two send paths
from tickets 82 and 83 are the only way a message reaches a dealer. The relay's inbound side is kept and
renamed as each case's **private reply-intake address**: a Buyer who would rather forward a dealer's email
than paste it forwards it there, and it enters the conversation through the existing untrusted intake and
document pipeline. Spec 2.1 §1.3, §2.2, §4 principles 11–13, AGT-03, AGT-04, AGT-06, AGT-07, §13.

**Blocked by:** 82 — Send dealer email from the Buyer's own email app.

**Status:** ready-for-agent after 82 — filed on 2026-10-05 from specification 2.1.

- [ ] No code path sends email to a dealer from a platform address. `RelayService.send`, the outbound relay adapters, the relay retry and delivery lookup from ticket 73, and their callers are removed or replaced by the send paths, and a test proves no outbound relay call remains.
- [ ] The platform footer, AI-disclosure line, and dealer opt-out are removed from drafts and from `OutboundGate`. The gate's amount, commitment, invented-fact and personal-data rules are unchanged, and its version is bumped.
- [ ] The mandate's autonomy mode is removed from the API, UI and new mandates with a forward-only migration. Stored mandates and their history stay readable, and no code can enable delegated sending.
- [ ] Each case's inbound address remains unguessable, accepts mail only while the case accepts it, and is shown to the Buyer as "Forward a dealer's email here". Forwarded mail is recorded as an inbound dealer message, its quoted forward headers are kept as content, and attachments and figures go through the existing intake.
- [ ] Ticket 78's inbound mail authentication prerequisite (provider webhook signature or equivalent, replay protection, binding mail to its case) applies to the reply-intake address before any real inbound provider is enabled.
- [ ] Existing relay-sent messages stay visible in each conversation with their recorded send path, and export and deletion still cover them.
- [ ] `docs/outbound-gate.md`, `docs/provider-inventory.md`, `docs/case-deletion-policy.md` and any other doc that describes platform-sent email are updated to the tool model.
- [ ] `mvn clean verify`, `npm test`, `npm run build` and `npx playwright test` pass.

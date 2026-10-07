# 78 — Update the threat model for the Buyer agent journey

**What to build:** The threat model covers what the service does today. `docs/hardening-threat-model.md` was last changed on 2026-09-17 for ticket 20, and covers the account-free analysis service only. Since then, specification 2.0 added Buyer Accounts and their session cookie, the dealer email relay and inbound dealer replies, the outbound gate, negotiation drafts built from dealer replies, release of the Buyer's contact details for a test drive, Buyer notification email, and the model execution harness. None of these have a row, a control list or a residual obligation. Found in the 2026-10-05 codebase security review.

**Blocked by:** 84 — Retire the platform relay and delegated mode. Sequenced on 2026-10-05: specification 2.1
removes platform-sent dealer email, so modelling the relay's outbound side before ticket 84 lands would describe a
path that is being deleted. Tickets 75, 76 and 77 update their own rows; this ticket can merge before or after them.

**Status:** ready-for-agent — filed on 2026-10-05 from a codebase security review; unblocked on 2026-10-06 when
ticket 84 retired outbound relay sending. Refined on 2026-10-07 after tickets 83, 87 and 88 merged: the relay is
inbound-only, the Buyer sends from their own email app or a connected mailbox, and the account now issues its own
case pass.

**Scope notes (2026-10-07):** documentation only, in `docs/hardening-threat-model.md`; no production code. Tickets 74,
75, 76, 77, 79 and 80 already added text and rows for the session cookie, deployment stage, case isolation inventory,
request-email limits, the client CSP and key rotation; extend those rows rather than duplicating them. The file is
still headed "Ticket 20 threat model"; retitle it for the current service. A control that no test proves is listed
as a residual obligation, not as a control.

- [ ] The scope statement covers Buyer Accounts and the account case pass (ticket 88), the two send paths (the Buyer's own email app, and Mailbox Connections from ticket 83: OAuth grants, encrypted send-only tokens, disconnect), each case's inbound "Forward a dealer's email here" address, negotiation, appointments, notifications and the model harness (ticket 87: Opus 5.5 default, calls disabled until the rollout gate admits it), and states the trust boundary of each: what is Buyer-controlled, dealer-controlled, operator-controlled and provider-controlled.
- [ ] New or extended rows cover at least: account takeover and sign-in link theft; session cookie theft and cross-site requests; account case pass theft or replay after sign-out, detachment or case-link rotation; OAuth callback forgery, mailbox token theft at rest, scope beyond sending, and access surviving disconnect; dealer impersonation and spoofed or forwarded inbound mail; hostile dealer content (prompt injection, oversized or malformed messages and attachments) reaching extraction, negotiation drafts or the Buyer's view; Buyer identity leaking into dealer-bound drafts; contact release beyond what the Buyer approved; notification misdelivery; Buyer data sent to the model provider; and the outbound gate being bypassed, or a connected-mailbox send retried into a duplicate.
- [ ] Each row names its controls and the executable tests that prove them, by class name, and only claims controls that the tests actually show.
- [ ] Inbound mail authentication (provider webhook signature or equivalent, replay protection, and binding forwarded mail to its case) is recorded as a prerequisite for enabling any real inbound provider, alongside `RelayProductionGuard`, and the equivalent prerequisites for real mailbox providers are recorded alongside `MailboxProductionGuard`.
- [ ] Residual obligations that only a deployment can prove are listed per row, and any gap that needs code is filed as a ticket and linked from its row.
- [ ] `PilotCopyClaimsTest` and the existing documentation checks still pass.

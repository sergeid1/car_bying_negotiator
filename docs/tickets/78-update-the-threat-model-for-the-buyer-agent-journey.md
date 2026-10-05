# 78 — Update the threat model for the Buyer agent journey

**What to build:** The threat model covers what the service does today. `docs/hardening-threat-model.md` was last changed on 2026-09-17 for ticket 20, and covers the account-free analysis service only. Since then, specification 2.0 added Buyer Accounts and their session cookie, the dealer email relay and inbound dealer replies, the outbound gate, negotiation drafts built from dealer replies, release of the Buyer's contact details for a test drive, Buyer notification email, and the model execution harness. None of these have a row, a control list or a residual obligation. Found in the 2026-10-05 codebase security review.

**Blocked by:** 84 — Retire the platform relay and delegated mode. Sequenced on 2026-10-05: specification 2.1
removes platform-sent dealer email, so modelling the relay's outbound side before ticket 84 lands would describe a
path that is being deleted. Tickets 75, 76 and 77 update their own rows; this ticket can merge before or after them.

**Status:** ready-for-agent — filed on 2026-10-05 from a codebase security review.

- [ ] The scope statement covers Buyer Accounts, the two send paths and Mailbox Connections (ticket 83: OAuth grants, encrypted send-only tokens, disconnect), each case's reply-intake address, negotiation, appointments, notifications and the model harness, and states the trust boundary of each: what is Buyer-controlled, dealer-controlled, operator-controlled and provider-controlled.
- [ ] New rows cover at least: account takeover and sign-in link theft; session cookie theft and cross-site requests; dealer impersonation and spoofed inbound mail; hostile dealer content (prompt injection, oversized or malformed messages and attachments) reaching extraction, negotiation drafts or the Buyer's view; Buyer identity leaking through the relay; contact release beyond what the Buyer approved; notification misdelivery; and the outbound gate being bypassed or retried into a duplicate send.
- [ ] Each row names its controls and the executable tests that prove them, by class name, and only claims controls that the tests actually show.
- [ ] Inbound mail authentication (provider webhook signature or equivalent, replay protection, and binding a reply to its relay thread) is recorded as a prerequisite for enabling any real relay provider, alongside the existing relay production guard.
- [ ] Residual obligations that only a deployment can prove are listed per row, and any gap that needs code is filed as a ticket and linked from its row.
- [ ] `PilotCopyClaimsTest` and the existing documentation checks still pass.

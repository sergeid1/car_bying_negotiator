# 83 — Connect Gmail or Outlook to send approved email

**What to build:** A signed-in Buyer can connect one Gmail or Outlook account with a send-only permission.
After that, approving a draft offers **Send from my email**, and the platform sends the exact approved
text through the Buyer's own account, so the dealer receives it from the Buyer's address and the message
appears in the Buyer's Sent folder. The platform never reads the Buyer's mailbox. This is the second send
path in spec 2.1, next to ticket 82's own-email-app path, and both stay available for every message.
Spec 2.1 §2.1, AGT-03, AGT-06, AGT-08, AGT-11, AGT-12, AGT-13, acceptance scenarios 21, 25 and 26.

**Blocked by:** 82 — Send dealer email from the Buyer's own email app (the send-path model, dealer
address and mark-sent state transitions). Enabling the path outside testing also needs operator work:
a Google Cloud project with a verified OAuth consent screen for `gmail.send`, and a Microsoft Entra app
registration with publisher verification for `Mail.Send`. Both need the company's domain, a published
Privacy Policy and Terms of Service, and a support address.

**Status:** complete against the fake adapter — `com.dealanalyzer.mailbox` (migration V46), as of 2026-10-06.
`MailboxSender` has a deterministic fake, a Gmail adapter (`users.messages.send`, RFC 5322) and a Graph adapter
(`sendMail`), selected by `mailbox.sender` (`fake`, `live`, `none`); `MailboxProductionGuard` refuses the fake under
pilot/prod/production. Connecting uses authorization code with PKCE and a single-use `state`; any grant beyond send plus
identity scopes is withdrawn and refused. Tokens and the address are AES-GCM encrypted under
`MAILBOX_TOKEN_ENCRYPTION_KEY` and deleted on disconnect, account deletion or `invalid_grant`. **Send from my email**
(`DealerHandoff.tsx`) goes through `DealerMessageHandoff.claimForMailbox`, which rechecks mandate, gate and state under
the case lock and records `CONNECTED_MAILBOX` / `DELIVERY_UNKNOWN` before the provider is called; a sent result applies
ticket 82's transition once, an unclear one asks the Buyer to choose **It was sent** or **Send again**, and nothing is
retried. Caps default to 10 per dealer, 20 per case, 40 per account per 24 h. Open: production enablement waits on the
Google and Microsoft verification above, and until then production must set `MAILBOX_SENDER=none` because the default
is `fake`; the live adapters have never run against Google or Microsoft; the ID token's signature is not verified (it
comes straight from the token endpoint over TLS); an interrupted send blocks Send again for that message for two
minutes.

- [x] A `MailboxSender` adapter interface has a deterministic fake, a Gmail adapter (`users.messages.send` with an RFC 5322 message) and a Microsoft Graph adapter (`sendMail`). A production guard refuses the fake under `pilot`, `prod` or `production`, like the existing relay and evidence guards.
- [x] Connecting uses OAuth 2.0 authorization code with PKCE and a `state` check, requests only `gmail.send` (Google) or `Mail.Send` plus `offline_access` (Microsoft) and the sending address, and records the provider, address, granted scope and time. A grant that includes any read scope is refused.
- [x] Refresh and access tokens are encrypted at rest with a dedicated key, never logged or exported, and deleted on disconnect, account deletion, or a provider revocation error. Disconnecting stops sends immediately; a send attempted afterwards is refused with an offer to use the own-email-app path.
- [x] **Send from my email** sends only the exact approved, gated text, rechecked under the case lock with the mandate and negotiation state. It records the `CONNECTED_MAILBOX` send path and applies the same state transition as ticket 82's mark-sent, exactly once.
- [x] A send whose outcome is unclear (timeout, connection loss, 5xx) becomes `DELIVERY_UNKNOWN` and is never retried automatically. Send-only access cannot look the message up, so the Buyer is asked to check their Sent folder and choose **It was sent** or **Send again**. Send again uses the same stored text and is audited.
- [x] Sends are metered and capped per dealer, per case and per account (AGT-11). Rate-limit and quota errors are stated plainly and never retried in a loop.
- [x] The account page shows the connected address and provider, explains in plain words that the platform can send but not read email, and has Disconnect. A Buyer without a connection still has every message available by the own-email-app path.
- [x] Mailbox connection rows are covered by account export (address and provider only, never tokens) and deletion, and the provider inventory lists Google and Microsoft as processors with what each receives.
- [x] The connect, send, unclear-outcome and disconnect flows work at 360 px in both themes, are in the axe sweep, and are covered by Playwright against the fake adapter.

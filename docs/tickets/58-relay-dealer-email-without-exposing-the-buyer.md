# 58 — Relay dealer email without exposing the Buyer

**What to build:** Each Buying Case gets a relay address. The platform can send email to a dealer and receive replies and attachments through an email relay adapter, without ever exposing the Buyer's address or identity. Every message identifies the platform as acting for a Buyer and as AI-assisted and carries a dealer opt-out. This ticket builds the channel; sending negotiation content is ticket 61. Spec AGT-03, AGT-04, AGT-11, §13.

**Blocked by:** 50 — Sign in with a passwordless Buyer Account; 57 — Approve a Negotiation Mandate.

**Status:** complete — a per-case Dealer relay in `com.dealanalyzer.relay` (migration V32), as of
2026-10-03. Each case gets an unguessable relay address that stops accepting mail once the case is being deleted, has
expired, or its account is pending deletion. Every outbound message carries a fixed footer (acting for a Buyer,
AI-assisted, opt-out by STOP reply or link); a dealer opt-out permanently blocks that relay identity and is shown in the
case's relay panel. `RelayService.send` refuses any rendered message containing the Buyer's email, phone or surname,
caps sends per case and per dealer per day, and meters every attempt; bodies are encrypted at rest, follow document
retention, and stay out of logs. Inbound mail is size-limited, malware-scanned, and stored as untrusted content;
attachments enter the existing document intake. The dealer opt-out link is `/api/v1/relay/opt-out/{token}` so it never
reveals the case id. Open: only a deterministic fake adapter exists — the real provider, its signed inbound webhook and
sender authentication wait on specification §18 prerequisite 10; contact release for a dealer is ticket 65; `send` has
no idempotency key, so a `DELIVERY_UNKNOWN` message must not be resent blindly.

- [x] A replaceable relay adapter sends and receives email; a deterministic fake drives the main test suite; the real provider is selected under specification §18 prerequisite 10 and is not enabled by this ticket.
- [x] Relay addresses are unguessable per case and stop accepting mail when the case or account is deleted.
- [x] Outbound messages carry a fixed footer identifying the platform as acting for a Buyer, AI-assisted, and an opt-out; a dealer opt-out permanently blocks that relay identity and is shown to the Buyer.
- [x] No outbound message can contain the Buyer's email, phone, or surname unless released (ticket 65); a test scans rendered messages.
- [x] Inbound mail is size-limited, malware-scanned, and stored as untrusted content; attachments enter the existing document intake.
- [x] Outbound volume is capped per dealer and per case; send attempts and deliveries are metered.
- [x] Message bodies follow the artifact retention and deletion policy and never enter ordinary logs.

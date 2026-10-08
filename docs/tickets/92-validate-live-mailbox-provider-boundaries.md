# 92 — Validate live mailbox provider boundaries

**What to build:** Prove the real Gmail/Graph OAuth and send boundaries before switching
`mailbox.sender=live`. Current acceptance evidence uses fake providers. `OAuthMailboxSender`
decodes identity-token claims returned by the TLS token endpoint but does not validate their
issuer, audience or expiry. Direct token-endpoint delivery can affect signature requirements;
it does not establish all identity claims. `MailboxProductionGuard` refuses fake senders and
requires a configured dedicated key; `DeploymentStageGuardTest` proves fake refusal across
deployment declarations, but not the live/missing-key path, OAuth app verification or provider
contract behavior. The send meter's in-flight window also needs concurrent/late-completion
evidence. Found during ticket 78's 2026-10-07 review.

**Blocked by:** None for code/stub contract tests; live release additionally requires Google
OAuth consent-screen verification, Microsoft publisher verification and deployed redirect checks.

**Status:** complete — no migration, as of 2026-10-07. Live Gmail and Graph adapters validate identity-token
issuer, audience, expiry, nonce and `azp` before using an address, relying on direct TLS token-endpoint delivery
instead of JWS signature checks (`docs/mailbox-provider-boundaries.md`). `MailboxProviderContractTest` covers both
adapters over local stub HTTP; `ConnectedMailboxAcceptanceTest` fences concurrent, slow, stale and restarted sends;
`DeploymentStageGuardTest` covers fake, missing-key and disabled configurations. Production connecting stays disabled
(`MAILBOX_SENDER=none`) until provider verification and the deployed checks listed there are recorded.

- [x] Validate identity issuer, audience/client binding, expiry and applicable nonce/authorized-party
  claims with provider-specific rules; document the trusted token-endpoint/signature decision.
- [x] Test authorization-code/PKCE and exact registered redirect handling, expired/reused/foreign
  state, send-only granted scopes and safe rejection of missing/malformed token and identity replies.
- [x] Add stub HTTP contract tests for Gmail and Graph covering exact recipient/subject/body,
  authentication failure, invalid_grant, quota, timeout, lost connection and 5xx; no hidden send retry.
- [x] Prove concurrent sends, crash/restart and slow sends past the in-flight window cannot dispatch
  an unintended second send or let late completion overwrite a newer decision. Explicit Buyer
  send-again remains a separate audited action that can duplicate an earlier UNKNOWN delivery.
- [x] Test `MailboxProductionGuard` across pilot/production declarations for fake refusal, missing
  key refusal and the disabled-provider configuration; do not present the guard as app verification.
- [x] Keep production connecting disabled until app/publisher verification, HTTPS redirect registration,
  least-privilege consent, secret management and live send/revocation checks are recorded.
- [x] Update the threat model with named executable evidence and remaining deployment obligations.

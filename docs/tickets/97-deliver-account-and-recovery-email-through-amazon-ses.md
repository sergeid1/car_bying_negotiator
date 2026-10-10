# 97 — Deliver account and recovery email through Amazon SES

**What to build:** Make Amazon SES the pilot's transactional mail provider for sign-in links, password
resets, case recovery and Buyer notifications. `SmtpRecoveryEmail` already sends through Spring Mail, and
SES offers an SMTP interface, so delivery itself is configuration. What is missing is what SES requires of a
sender: bounce and complaint handling, so repeated bounces cannot get the account paused, and a
suppression list the application respects. Chosen by the operator on 2026-10-08 over Postmark and Resend
for long-term cost. Account and domain setup is the operator's, under ticket 93.

**Blocked by:** None for code, using fake SES notifications. Live delivery needs ticket 93's AWS account,
the verified sending domain with DKIM, SPF and DMARC in Cloudflare DNS, and SES production access (sandbox
exit). Migration V51 is reserved for the suppression list (V50 went to ticket 100).

**Status:** code complete — migration V51, as of 2026-10-09; live verification blocked on the operator (ticket
93). `POST /api/v1/email/ses-notifications` verifies the SNS signature (JDK crypto, versions 1 and 2) and the
regional certificate host, confirms subscriptions only for `email.ses.topic-arns`, and refuses everything else
before parsing. Permanent bounces and complaints add a keyed hash to `email_suppressions`; `SmtpRecoveryEmail`
skips suppressed recipients for every purpose without changing the Buyer-facing response, and account deletion
removes the account's entry. `RecoveryEmailProductionGuard` refuses a loopback host or missing topic
configuration under a production-like `DeploymentStage`. SES SMTP is configured through `SES_SMTP_*`; the
development profile keeps Mailpit without auth or STARTTLS. A delivered sign-in email and mailbox-simulator
bounce and complaint remain to be recorded once ticket 93's setup exists.

- [x] Configuration for the SES SMTP endpoint (region, port 587 with STARTTLS, SMTP credentials from the
      deployment secret store) is documented in the hardening runbook; credentials never appear in the
      repository, command arguments or logs.
- [x] Bounce and complaint notifications arrive through SNS to an HTTPS endpoint. The endpoint verifies
      the SNS message signature and certificate host, confirms subscriptions only for the configured topic
      ARNs, and refuses everything else before parsing content.
- [x] Permanent bounces and complaints add the address (stored as a keyed hash, not plaintext) to a
      suppression list (V51). Sign-in, recovery and notification sends to a suppressed address are skipped
      without revealing suppression to the requester; the Buyer-facing response stays the same.
- [x] Mail carries a configured From address on the company domain and a plain-text body; no tracking
      pixels or link rewriting are enabled in SES.
- [x] A pilot or production deployment refuses to start with recovery email configured against a loopback
      host or without the bounce endpoint's topic configuration, using the shared `DeploymentStage` decision.
- [x] Tests cover signed and forged SNS messages, subscription confirmation, bounce, complaint, suppressed
      send and account deletion removing the account's suppression entry when policy allows.
- [ ] `docs/provider-inventory.md` lists Amazon SES (region, data: recipient address and message text) and
      `docs/pilot-readiness-record.md` is updated. Deployed checks (a delivered sign-in email, a simulated
      bounce via the SES mailbox simulator) are recorded once ticket 93's setup exists.

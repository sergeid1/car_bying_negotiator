# 94 — Put sign-in in the header and catch mail locally

**What to build:** Make the Buyer Account findable and testable end to end. Ticket 50 built passwordless
accounts, but the only entry point is the "Buyer Account" section near the bottom of the page
(`App.tsx`, rendered by `BuyerAccount.tsx`). The masthead shows no Sign in, no signed-in state and no Sign
out, and nothing explains that entering an email creates the account. Locally, `SmtpRecoveryEmail` has no
mail server, so a sign-in request fails with "The sign-in link could not be sent" and nobody can finish a
sign-in outside the tests. Filed on 2026-10-08 after the operator could not find account sign-up.

**Blocked by:** None.

**Status:** complete — no migration, as of 2026-10-08. The masthead's `AccountHeader` shows Sign in, or the account
email and Sign out, from the state `BuyerAccount` publishes through `onControls`; there is no second account fetch.
`docker compose --profile development up -d database mailpit` starts Mailpit on loopback ports, and the Spring
`development` profile (`application-development.properties`) points mail at it. `RecoveryEmailProductionGuard`
refuses enabled recovery mail with a missing, loopback, Docker-host or catcher SMTP host under any production-like
declaration. Open: the Playwright account flow captures the link with stubbed account APIs, so no single test runs
backend, SMTP and browser together; a renamed remote catcher or public DNS name resolving to loopback is not
caught by hostname alone.

- [x] The masthead shows **Sign in** when signed out and the account email (truncated on narrow screens)
      with **Sign out** when signed in, sharing one source of truth with `BuyerAccount` and
      `AccountSignedInContext`; signing out from either place updates both.
- [x] Sign in opens the account panel (or a focused sign-in view) with copy that states plainly that
      entering an email creates the account if none exists, that no account is needed to analyze a deal,
      and how the case link differs from the account. Copy passes `PilotCopyClaimsTest`.
- [x] A local development mail catcher is documented and runnable with one command (for example a
      Mailpit container on the existing dev setup) with `SPRING_MAIL_HOST`, port and
      `CASE_RECOVERY_FROM` defaults for development only. Sign-in, case recovery and notification
      emails arrive there. No production default points at it.
- [x] A deployment declared pilot or production refuses to start with recovery email enabled but pointed
      at a loopback or catcher host, using the shared `DeploymentStage` decision.
- [x] Playwright covers request link → read it from the catcher (or an equivalent test hook) → finish
      sign-in → header shows the account → sign out from the header → header shows Sign in, at desktop
      and 360 px widths, with the new header controls in the axe sweep.
- [x] README or `docs/buyer-account-policy.md` explains how to try the account flow locally.

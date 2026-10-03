# 50 — Sign in with a passwordless Buyer Account

**What to build:** A Buyer can create an account and sign in with an expiring single-use email link, keep contact details encrypted in one place, attach an existing Phase 1 Buying Case to the account, and export or delete the account. Journeys that span days and need notifications (Phases 2–4) build on it. Phase 1 analysis stays usable without an account. Spec §8.8 ACCT-01–03.

**Blocked by:** 03 — Create and securely reopen an ephemeral Buying Case; 16 — Recover, export, and download a Buying Case; 17 — Expire and delete the complete Buying Case lifecycle.

**Status:** complete — passwordless Buyer Accounts in `com.dealanalyzer.account` (migration V29), as of
2026-10-03. Sign-in uses single-use 256-bit email links through the shared `RecoveryEmail` adapter, capped
at three unused links per address; sessions are HMAC-stored, expire, and are revoked server-side at sign-out.
Contact details are AES-GCM encrypted once per account and never logged. Cases attach by case link; account
export bundles each attached case export; account deletion runs every attached case through the case deletion
lifecycle and verifies the account graph. Open: backup-restore deletion replay covers cases, not account rows;
the session token is held in `localStorage`. See [Buyer Account policy](../buyer-account-policy.md).

- [x] Sign-in uses an expiring, single-use, high-entropy email link through the existing email adapter; there are no passwords, and a used or expired link is refused with a clear message.
- [x] Sessions are revocable, expire, and survive a page reload; signing out revokes the session server-side.
- [x] Contact details (email, optional phone, first and last name) are stored once, encrypted at rest, and never written to logs, analytics, or operator tooling.
- [x] A Phase 1 Buying Case opened by its case link can be attached to the signed-in account; case-link access keeps working.
- [x] Account export includes every attached case's export; account deletion follows the case deletion lifecycle for every attached case and the account record, and is verified like case deletion.
- [x] Creating a case without an account is unchanged and still covered by existing tests.
- [x] The sign-in and account screens work at 360 px in both themes and are registered in `Accessibility.test.tsx`.

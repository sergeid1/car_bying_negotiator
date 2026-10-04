# 64 — Notify the Buyer by email

**What to build:** The Buyer gets an email when a dealer replies, when a draft needs approval, when a mandate is about to expire, and for appointments, with per-type opt-out. Emails carry no sensitive content, only a prompt to open the journey. Spec NOTE-01.

**Blocked by:** 50 — Sign in with a passwordless Buyer Account.

**Status:** complete — Buyer email notifications in `com.dealanalyzer.notification` (migration V31), as of
2026-10-03. `BuyerNotifications.notify` sends a fixed prompt and the journey link through the `RecoveryEmail`
adapter; four optional types can each be turned off, and `ACCOUNT_SECURITY` cannot. Each event key is attempted
at most once (stored as a keyed hash), optional and security email have separate hourly quotas, and every attempt
records its delivery state without address, body or link. `MANDATE_EXPIRING` is wired to ticket 57 through
`MandateExpiryNotifier`; dealer-reply, draft and appointment producers arrive with tickets 60, 61 and 65. Open:
`SENT` means the adapter accepted the message; failed or uncertain sends are not retried. See
[Buyer Account policy](../buyer-account-policy.md#notifications).

- [x] Notifications are sent through the existing email adapter to the account's address.
- [x] No notification contains amounts, dealer names, VINs, or document content; it links to the journey, which requires sign-in.
- [x] The Buyer can turn each notification type off; mandatory account-security emails cannot be turned off.
- [x] Notifications are deduplicated and rate-limited per Buyer.
- [x] Delivery state is recorded; delivery content and addresses never enter ordinary logs.

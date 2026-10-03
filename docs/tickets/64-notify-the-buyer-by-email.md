# 64 — Notify the Buyer by email

**What to build:** The Buyer gets an email when a dealer replies, when a draft needs approval, when a mandate is about to expire, and for appointments, with per-type opt-out. Emails carry no sensitive content, only a prompt to open the journey. Spec NOTE-01.

**Blocked by:** 50 — Sign in with a passwordless Buyer Account.

**Status:** blocked on 50 — filed on 2026-10-03 from specification 2.0.

- [ ] Notifications are sent through the existing email adapter to the account's address.
- [ ] No notification contains amounts, dealer names, VINs, or document content; it links to the journey, which requires sign-in.
- [ ] The Buyer can turn each notification type off; mandatory account-security emails cannot be turned off.
- [ ] Notifications are deduplicated and rate-limited per Buyer.
- [ ] Delivery state is recorded; delivery content and addresses never enter ordinary logs.

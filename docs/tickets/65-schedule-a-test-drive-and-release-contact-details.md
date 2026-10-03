# 65 — Schedule a test drive and release contact details

**What to build:** After agreeing a price, the Buyer gives availability, the platform proposes test-drive times to the dealer through the relay, and the dealer confirms one. When booking, the Buyer chooses which contact details to release to that dealer. The Buyer receives a calendar file and a reminder. Spec §5.8, APPT-01, APPT-02, ACCT-03.

**Blocked by:** 63 — Accept a written quote as the Agreed Price; 58 — Relay dealer email without exposing the Buyer; 64 — Notify the Buyer by email.

**Status:** blocked on 63 — filed on 2026-10-03 from specification 2.0.

- [ ] The Buyer enters availability windows; the proposal to the dealer passes the gate and sends after Buyer approval.
- [ ] A dealer's confirmation is captured from the conversation and shown to the Buyer to confirm before it becomes an appointment.
- [ ] Booking asks the Buyer which contact details to release; only those are sent, the release is audited, and it can be withheld entirely.
- [ ] The Buyer receives a standards-compliant calendar file and a reminder email, and the appointment references the Agreed Price.
- [ ] Cancelling or rescheduling goes through the relay and updates the appointment.
- [ ] The journey state becomes `TEST_DRIVE_SCHEDULED`.

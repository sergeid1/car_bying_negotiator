# 65 — Schedule a test drive and release contact details

**What to build:** After agreeing a price, the Buyer gives availability, the platform proposes test-drive times to the dealer through the relay, and the dealer confirms one. When booking, the Buyer chooses which contact details to release to that dealer. The Buyer receives a calendar file and a reminder. Spec §5.8, APPT-01, APPT-02, ACCT-03.

**Blocked by:** 63 — Accept a written quote as the Agreed Price; 58 — Relay dealer email without exposing the Buyer; 64 — Notify the Buyer by email.

**Status:** complete — test-drive scheduling in `com.dealanalyzer.appointment` (migration V42), as of 2026-10-04.
The Buyer enters one to three availability windows; fixed platform wording proposes one-hour options, never says the
Buyer will buy, passes the outbound gate and sends once through the relay after the Buyer approves the exact text.
Dealer replies after the proposal are shown as quoted content with a suggested option only when exactly one is
named; nothing is booked until the Buyer confirms. Booking requires the signed-in Buyer Account that holds the case
and asks which contact fields to release, none selected by default; `AccountContactRelease` decrypts only the chosen
fields, the relay lifts its identity screen only for those exact values, and the release is audited by field name,
never value (`test_drive_contact_releases`, `CONTACT_DETAILS_RELEASED`). The Buyer downloads an RFC 5545 calendar
file referencing the Agreed Price from the signed-in case view and receives an `APPOINTMENT` email on booking and a
reminder before the drive; following ticket 64, notification emails carry no content, so the file is not attached.
Reschedule and cancel go through the gate, approval and relay. `LinkedJourneyAppointment` moves a linked journey to
`TEST_DRIVE_SCHEDULED` and back to `AGREED` when the last appointment is cancelled. Case export and deletion cover the
three new tables. Open: every dealer message needs an active mandate, so a mandate that expires after agreement
blocks scheduling until the Buyer approves a new one; the gate's fail-closed personal-data match can refuse a message
for a very short first name or a released email that contains the unreleased surname; the relay still has no
idempotency key, so an unclear delivery is not resent automatically (ticket 73 adds the key, a delivery check and a
Buyer retry).

- [x] The Buyer enters availability windows; the proposal to the dealer passes the gate and sends after Buyer approval.
- [x] A dealer's confirmation is captured from the conversation and shown to the Buyer to confirm before it becomes an appointment.
- [x] Booking asks the Buyer which contact details to release; only those are sent, the release is audited, and it can be withheld entirely.
- [x] The Buyer receives a standards-compliant calendar file and a reminder email, and the appointment references the Agreed Price.
- [x] Cancelling or rescheduling goes through the relay and updates the appointment.
- [x] The journey state becomes `TEST_DRIVE_SCHEDULED`.

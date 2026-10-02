# 42 — Attribute qualified uploads to first-party acquisition channels

**What to build:** The parts of ticket 34 that need no channel, partner, or offer. A Buyer arriving
from a link that carries an allow-listed channel code starts a case tagged with that channel, and an
operator can see qualified uploads per channel within the supported jurisdiction, cost per qualified
upload from operator-entered spend, and cost per paid case as unavailable until purchases exist.
Ticket 34 keeps choosing and testing the channels, partner arrangements, and their results.

**Blocked by:** 19 — Capture privacy-minimized feedback and pilot metrics; 23 — Start a real Buying
Case from Buyer-supplied vehicle and location. Ticket 32 is not required for this measurement.

**Status:** ready-for-agent

- [ ] A link parameter carrying a channel code is read once by the client when a case starts, sent
      with case creation, and removed from the address bar. Only codes on an operator-configured allow
      list are stored; an unknown, malformed, or absent code is stored as no channel and never fails or
      delays case creation.
- [ ] No cookies, local storage identifiers, fingerprinting, third-party scripts, referrer capture,
      or cross-site Buyer tracking are introduced; the channel code is the only acquisition data kept.
- [ ] The channel is stored with the case and deleted with it, as recorded in the case deletion
      policy; any count that survives deletion is anonymous, like the existing cohort totals.
- [ ] Pilot metrics report, per channel and for no channel, qualified uploads within the supported
      jurisdiction as the primary measure, with denominators; case starts are reported only as secondary
      context.
- [ ] An operator can record channel spend for a stated period; cost per qualified upload is computed
      from it, and cost per paid case is reported as unavailable while no purchase record exists.
- [ ] A channel configured as a referral partnership shows the Buyer its configured disclosure before
      upload; a test proves the analysis, report, and pricing never read the channel.
- [ ] Dealer-paid lead and dealer outreach channel kinds cannot be configured.
- [ ] Channel management and metrics endpoints require the `SUPPORT` operator credential; Buyer
      credentials cannot read or change them.
- [ ] `docs/pilot-metrics-policy.md` documents the channel measurement, its denominators, and its
      privacy limits; new UI passes the accessibility sweep and the 360 px gate.

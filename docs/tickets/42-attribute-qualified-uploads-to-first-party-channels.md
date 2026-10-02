# 42 — Attribute qualified uploads to first-party acquisition channels

**What to build:** The parts of ticket 34 that need no channel, partner, or offer. A Buyer arriving
from a link that carries an allow-listed channel code starts a case tagged with that channel, and an
operator can see qualified uploads per channel within the supported jurisdiction, cost per qualified
upload from operator-entered spend, and cost per paid case as unavailable until purchases exist.
Ticket 34 keeps choosing and testing the channels, partner arrangements, and their results.

**Blocked by:** 19 — Capture privacy-minimized feedback and pilot metrics; 23 — Start a real Buying
Case from Buyer-supplied vehicle and location. Ticket 32 is not required for this measurement.

**Status:** complete — an allow-listed `?channel=` code is read once at case start, stored with the
case and deleted with it, and SUPPORT-only pilot metrics report qualified uploads per channel with cost
per qualified upload from entered spend, as of 2026-10-02. No channels, disclosures or spend are
configured; cost per paid case stays unavailable until purchases exist.

- [x] A link parameter carrying a channel code is read once by the client when a case starts, sent
      with case creation, and removed from the address bar. Only codes on an operator-configured allow
      list are stored; an unknown, malformed, or absent code is stored as no channel and never fails or
      delays case creation.
- [x] No cookies, local storage identifiers, fingerprinting, third-party scripts, referrer capture,
      or cross-site Buyer tracking are introduced; the channel code is the only acquisition data kept.
- [x] The channel is stored with the case and deleted with it, as recorded in the case deletion
      policy; any count that survives deletion is anonymous, like the existing cohort totals.
- [x] Pilot metrics report, per channel and for no channel, qualified uploads within the supported
      jurisdiction as the primary measure, with denominators; case starts are reported only as secondary
      context.
- [x] An operator can record channel spend for a stated period; cost per qualified upload is computed
      from it, and cost per paid case is reported as unavailable while no purchase record exists.
- [x] A channel configured as a referral partnership shows the Buyer its configured disclosure before
      upload; a test proves the analysis, report, and pricing never read the channel.
- [x] Dealer-paid lead and dealer outreach channel kinds cannot be configured.
- [x] Channel management and metrics endpoints require the `SUPPORT` operator credential; Buyer
      credentials cannot read or change them.
- [x] `docs/pilot-metrics-policy.md` documents the channel measurement, its denominators, and its
      privacy limits; new UI passes the accessibility sweep and the 360 px gate.

## Implementation notes — 2026-10-02

- Migration `V26__create_acquisition_channels.sql` adds `acquisition_channels` (kinds limited to
  `EXPLAINER_CONTENT` and `REFERRAL_PARTNERSHIP`), `analysis_cases.acquisition_channel`, immutable
  `acquisition_channel_spend`, and anonymous `acquisition_channel_totals` kept on deletion.
- Operator endpoints live under `/api/v1/operator/pilot/channels`; `/metrics` embeds `acquisitionChannels`.
- Cost per qualified upload is cumulative spend over cumulative qualified uploads, because the
  anonymous deletion counters carry no dates.
- The standing footer says no commercial party pays for referrals; a partner-pays arrangement needs
  that disclosure revised first (ticket 34).

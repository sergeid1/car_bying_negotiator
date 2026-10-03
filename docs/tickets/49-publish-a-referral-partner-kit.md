# 49 — Publish a referral partner kit

**What to build:** The partner-facing part of ticket 34's referral channel that needs no signed
partner. A consumer educator or independent inspection service can read one page that explains what
the product does, how a referral link works, what Buyers are told about the referral, and the terms
of the arrangement, and can compose their own referral links from a channel code the operator gave
them. Ticket 34 keeps recruiting partners, configuring their channels, and reporting results.

**Blocked by:** 42 — Attribute qualified uploads to first-party channels; 47 — Publish quote and fee
explainer guides.

**Status:** complete — the kit at `?page=referral-partners`, linked from the footer, states who can
refer, the terms, and what Buyers are told, and builds copyable referral links in the browser, as of
2026-10-03. No channel is seeded; ticket 34 keeps recruiting partners and configuring their channels.

- [x] The kit is reachable without starting a case at a stable shareable URL (`?page=referral-partners`)
      with no new runtime dependency or router, and from a link in the page footer. An unknown `page`
      value shows the usual start screen.
- [x] It names who the kit is for (consumer educators and independent inspection services) and states
      that dealers, lenders, and dealer-affiliated parties cannot be referral partners, per
      specification §2.2.
- [x] It states the terms consistently with the standing footer: partners are not paid for referrals
      and do not pay for placement, the Buyer is shown a disclosure of the referral, and no partner
      relationship affects any market range, rating, finding, or suggested boundary.
- [x] It shows the "How you reached us" disclosure panel as a clearly labelled example, using the same
      component the case view uses.
- [x] A link builder takes a channel code, validates it against ticket 42's format (1–32 lower-case
      letters, digits, and hyphens, starting with a letter or digit), and composes copyable links to the
      start screen and to both explainer guides carrying `?channel=<code>`. It works entirely in the
      browser: no request, cookie, or storage, and it states that the operator must configure the code
      before referrals are counted.
- [x] Copy describes the product in general terms already used elsewhere, attributes no savings to the
      product, avoids "AI negotiator" and "safe to sign", and gives no legal advice.
      `PilotCopyClaimsTest` passes, including identifiers and comments.
- [x] The kit reads correctly in the light and dark themes, is registered in `Accessibility.test.tsx`,
      and a Playwright spec proves the page and the link builder at 360 px without horizontal overflow.
- [x] `docs/pilot-metrics-policy.md` gains a partner onboarding section: configure a
      `REFERRAL_PARTNERSHIP` channel with its disclosure, give the partner the code and the kit URL.

## Implementation notes — 2026-10-03

- The kit is `frontend/src/ReferralPartnerKit.tsx`, selected by `?page=` in `App.tsx` like the guides.
  The labelled example uses the case view's `ReferralDisclosure` component.
- The link builder checks codes with `isChannelCode` (ticket 42's format, now exported from
  `AcquisitionChannel.tsx`) and builds absolute links to the start screen and both guides.
- A guide opened with a `channel` parameter now passes that code to its start link instead of its own,
  so a partner's guide link keeps the referral. A guide opened without one behaves as before.
- Covered by `ReferralPartnerKit.test.tsx`, `Accessibility.test.tsx`, and
  `e2e/referral-partner-kit-narrow-width.spec.ts` at 360 px in both themes. Onboarding steps are in
  `docs/pilot-metrics-policy.md`.

# 49 — Publish a referral partner kit

**What to build:** The partner-facing part of ticket 34's referral channel that needs no signed
partner. A consumer educator or independent inspection service can read one page that explains what
the product does, how a referral link works, what Buyers are told about the referral, and the terms
of the arrangement, and can compose their own referral links from a channel code the operator gave
them. Ticket 34 keeps recruiting partners, configuring their channels, and reporting results.

**Blocked by:** 42 — Attribute qualified uploads to first-party channels; 47 — Publish quote and fee
explainer guides.

**Status:** ready-for-agent — filed on 2026-10-03.

- [ ] The kit is reachable without starting a case at a stable shareable URL (`?page=referral-partners`)
      with no new runtime dependency or router, and from a link in the page footer. An unknown `page`
      value shows the usual start screen.
- [ ] It names who the kit is for (consumer educators and independent inspection services) and states
      that dealers, lenders, and dealer-affiliated parties cannot be referral partners, per
      specification §2.2.
- [ ] It states the terms consistently with the standing footer: partners are not paid for referrals
      and do not pay for placement, the Buyer is shown a disclosure of the referral, and no partner
      relationship affects any market range, rating, finding, or suggested boundary.
- [ ] It shows the "How you reached us" disclosure panel as a clearly labelled example, using the same
      component the case view uses.
- [ ] A link builder takes a channel code, validates it against ticket 42's format (1–32 lower-case
      letters, digits, and hyphens, starting with a letter or digit), and composes copyable links to the
      start screen and to both explainer guides carrying `?channel=<code>`. It works entirely in the
      browser: no request, cookie, or storage, and it states that the operator must configure the code
      before referrals are counted.
- [ ] Copy describes the product in general terms already used elsewhere, attributes no savings to the
      product, avoids "AI negotiator" and "safe to sign", and gives no legal advice.
      `PilotCopyClaimsTest` passes, including identifiers and comments.
- [ ] The kit reads correctly in the light and dark themes, is registered in `Accessibility.test.tsx`,
      and a Playwright spec proves the page and the link builder at 360 px without horizontal overflow.
- [ ] `docs/pilot-metrics-policy.md` gains a partner onboarding section: configure a
      `REFERRAL_PARTNERSHIP` channel with its disclosure, give the partner the code and the kit URL.

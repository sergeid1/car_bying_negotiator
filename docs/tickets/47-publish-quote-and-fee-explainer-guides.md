# 47 — Publish quote and fee explainer guides as a first-party channel

**What to build:** The content part of ticket 34 that needs no partner and no spend. A Buyer holding a
dealer quote can read a short guide on how to read an out-the-door quote and which dealer fees to ask
about, and start a case from it. The link carries a first-party channel code, so ticket 42's
measurement attributes the qualified uploads that follow. Ticket 34 keeps choosing and testing
channels, partner arrangements, spend, and reporting their results.

**Blocked by:** 42 — Attribute qualified uploads to first-party channels.

**Status:** complete — two explainer guides at `?guide=out-the-door-quote` and `?guide=dealer-fees`
are linked from the start screen and end with a start action carrying the channel codes
`guide-otd-quote` and `guide-dealer-fees`, as of 2026-10-03. No channel is seeded; an operator must
configure both codes before guide cases are counted, and ticket 34 keeps testing them as a channel.

- [x] Two guides, "How to read an out-the-door quote" and "Which dealer fees to ask about", are
      reachable without starting a case at stable shareable URLs (`?guide=out-the-door-quote` and
      `?guide=dealer-fees`) with no new runtime dependency, and from a link on the start screen.
- [x] Guide content describes what the product already checks, in general terms. It states no
      jurisdiction-specific fee limits or legal rules, gives no legal advice, and uses no figures that
      are not labelled as an example.
- [x] Each guide ends with a start action that begins case intake with its channel code
      (`guide-otd-quote` and `guide-dealer-fees`) through ticket 42's existing `channel` parameter. An
      unconfigured code starts a case with no channel, exactly as today.
- [x] Guides set no cookies, load no third-party resources, and add no tracking beyond the channel code.
- [x] Copy avoids "AI negotiator" and "safe to sign", attributes no savings to the product, and describes
      a verified quote change as a quote change. `PilotCopyClaimsTest` passes, including identifiers and
      comments.
- [x] Both guides read correctly in the light and dark themes, are registered in `Accessibility.test.tsx`,
      and a Playwright spec proves both guides and their start actions at 360 px without horizontal
      overflow.
- [x] `docs/pilot-metrics-policy.md` lists the two channel codes and states that an operator must
      configure them before they are counted.

## Implementation notes — 2026-10-03

- Guides live in `frontend/src/ExplainerGuides.tsx` and are selected by `?guide=`; an unknown name shows
  the usual start screen. They are static: no fetch, storage, cookies, or third-party resources.
- The start action is a page load to `?channel=<code>#intake-heading`, so ticket 42's existing `channel`
  handling reads, strips, and sends the code unchanged.
- The only figures are one example list, each row labelled as an example. The disclaimer avoids the word
  "advice", which `PilotCopyClaimsTest` flags anywhere in `frontend/src`.
- Covered by `ExplainerGuides.test.tsx`, `Accessibility.test.tsx`, and
  `e2e/explainer-guides-narrow-width.spec.ts` at 360 px in both themes.

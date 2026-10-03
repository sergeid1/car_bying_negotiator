# 47 — Publish quote and fee explainer guides as a first-party channel

**What to build:** The content part of ticket 34 that needs no partner and no spend. A Buyer holding a
dealer quote can read a short guide on how to read an out-the-door quote and which dealer fees to ask
about, and start a case from it. The link carries a first-party channel code, so ticket 42's
measurement attributes the qualified uploads that follow. Ticket 34 keeps choosing and testing
channels, partner arrangements, spend, and reporting their results.

**Blocked by:** 42 — Attribute qualified uploads to first-party channels.

**Status:** ready-for-agent

- [ ] Two guides, "How to read an out-the-door quote" and "Which dealer fees to ask about", are
      reachable without starting a case at stable shareable URLs (`?guide=out-the-door-quote` and
      `?guide=dealer-fees`) with no new runtime dependency, and from a link on the start screen.
- [ ] Guide content describes what the product already checks, in general terms. It states no
      jurisdiction-specific fee limits or legal rules, gives no legal advice, and uses no figures that
      are not labelled as an example.
- [ ] Each guide ends with a start action that begins case intake with its channel code
      (`guide-otd-quote` and `guide-dealer-fees`) through ticket 42's existing `channel` parameter. An
      unconfigured code starts a case with no channel, exactly as today.
- [ ] Guides set no cookies, load no third-party resources, and add no tracking beyond the channel code.
- [ ] Copy avoids "AI negotiator" and "safe to sign", attributes no savings to the product, and describes
      a verified quote change as a quote change. `PilotCopyClaimsTest` passes, including identifiers and
      comments.
- [ ] Both guides read correctly in the light and dark themes, are registered in `Accessibility.test.tsx`,
      and a Playwright spec proves both guides and their start actions at 360 px without horizontal
      overflow.
- [ ] `docs/pilot-metrics-policy.md` lists the two channel codes and states that an operator must
      configure them before they are counted.

# 105 — Start at the dealer with a photo

**What to build:** An "I'm at the dealer" entry point that opens the camera first. The Buyer photographs
the pencil sheet, four-square or buyer's order, and the platform reads the VIN from it, attaches it to the
open journey's agreed car when the VIN matches (Desk Check) or starts a fresh case for that car when it
does not, and asks only what the paperwork cannot answer: whether there is a trade, what is owed on it,
and outside financing if the worksheet shows a loan. A Buyer with no journey can use it too. Spec 5.9,
DESK-03.

**Blocked by:** 66 — Check desk paperwork against the Agreed Price; 100 — Record the trade-in and what is
owed on it. Reading real photos needs 36 — the Claude extraction provider enabled; until then this flow
works only with text-bearing paperwork and Buyer-typed terms.

**Status:** open — agent-ready (100 complete). Field use waits on ticket 36.

- [ ] The entry point is in the header or journey on a phone and reaches the camera in one tap.
- [ ] A VIN read from the paperwork routes to the agreed car's Desk Check or to a fresh case, and the
      Buyer confirms the routing before anything is compared.
- [ ] Questions appear only when needed and reuse the journey's Trade Vehicle and payoff when they exist.
- [ ] When a photo cannot be read, the Buyer can type the four-square's numbers in a short form instead of
      being stopped.
- [ ] The first result leads with the out-the-door total (or its absence), the largest finding and the
      desk question for it, readable without scrolling at 360 px.
- [ ] Upload-to-first-result latency is measured on a phone over a cellular network and recorded.
- [ ] The flow is registered in `Accessibility.test.tsx` and the 360 px browser gate in both themes.

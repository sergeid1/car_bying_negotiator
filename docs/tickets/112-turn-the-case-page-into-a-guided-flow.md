# 112 — Turn the case page into a guided flow

**What to build:** A case page that shows the Buyer where they are and what to do next, instead of every tool
at once. Today an open case keeps the marketing hero at the top and stacks ten sections into one scroll (about
5,000 px on desktop, 6,200 px on a phone). Desk Check comes before the quote request and the offer analysis comes
last. Only two sections carry step numbers ("Step 2 of 3", "Step 3 of 3"), and sections that cannot do anything
yet still show, with "unavailable" or "Requires a verified offer" messages. Operator request on 2026-10-12,
from a design review of the running client; the operator approved the progress bar and greyed-out later steps.

**Blocked by:** none. It runs in parallel with 110 and 111 (operator decision, 2026-10-12); the three share
`App.tsx`, `styles.css` and `Accessibility.test.tsx`, so each keeps to the file ownership in its coordinator
assignment.

**Status:** open — agent-ready. The step names below are placeholders until the operator confirms them. Client only; if a step's
"done" state cannot be read from the existing case endpoints, raise it with the coordinator before adding an
endpoint.

## The page

- With a case open, the hero is hidden. The top of the page shows the car (year, make, model, trim when known),
  the dealer and how the Buyer is paying, and a **This case** menu holding the private link, the recovery email
  opt-in, downloads and delete. That menu replaces the "This case" header button left by ticket 110.
- Under it, a progress bar with four steps. Proposed names; the operator may rename them:
  1. **Get quotes**: request an itemized quote; dealer messages.
  2. **Compare & negotiate**: offer verification and analysis, price boundaries, the negotiation mandate,
     dealer and outside financing.
  3. **At the dealer**: dealer paperwork upload, trade-in, Desk Check.
  4. **Final check**: the report against the agreed price, before signing.
- Only the current step is open. Finished steps fold into one summary line that can be reopened. Later steps
  are greyed out, with one sentence saying what unlocks them (for example, "Unlocks when you have a dealer
  quote"). A section with nothing to do yet shows that sentence, not an error.
- A Buyer can still open any step by hand. The bar guides; it does not lock anyone out of their own case.
- "I'm at the dealer" opens step 3 directly, as ticket 105's flow does today.
- Rename the remaining internal terms in Buyer-facing headings: "Negotiation Mandate", "price boundaries",
  "freeze your dealer offer", "Buying Case". The operator approves the new wording, and it must pass
  `PilotCopyClaimsTest` and `docs/grounded-language-policy.md`.

## Afterwards

Ticket 107's how-it-works walkthrough shows product screens from before 110–112. Once this ticket lands, recapture
those screens and check its step captions against the new flow and step names.

## Done when

- [ ] With a case open, the first screen at 1440 px and 360 px shows the car, the progress bar and the current
      step's main action, with no hero.
- [ ] The current step is chosen from the case's state, and is correct for: a new case, a case with a quote, a
      case with a verified offer, and a case with desk paperwork.
- [ ] No section shows an error just because its step has not been reached.
- [ ] The progress bar is a labelled list with `aria-current="step"`, works by keyboard, and every step panel
      is registered in the axe sweep in `Accessibility.test.tsx`.
- [ ] `npm test`, `npm run build` and `npx playwright test` pass, including every case-section narrow-width
      spec in both themes; the coordinator also runs `mvn clean verify` on the result.

# 111 — Simplify the home page and the start form

**What to build:** A home page with one clear thing to do, and a start form that asks only what a Buyer
knows. Today the hero has no button. It describes four jobs (find cars, check quotes, draft emails, check
paperwork) and lists engineering badges ("Deterministic, versioned math", "Ephemeral by default"). "Start
my case" sits about 1,200 px down, below the example report card. The form asks for a dealer ZIP code, which
Buyers do not know, and requires the trim. The header carries a "Scope" link that Buyers do not understand.
Operator request on 2026-10-12, from a design review of the running client.

**Blocked by:** 110 — Make sign-in one email box and one button, only because both touch the header and the
same hot files (`App.tsx`, `styles.css`, `Accessibility.test.tsx`). Ticket 107 also adds a link from the home
page to the walkthrough; whichever lands second keeps that link as a secondary text link, not a second main
button.

**Status:** open — agent-ready. Client plus a small intake API change; no migration is expected, because
`analysis_cases.dealer_location` is already `VARCHAR(200)`.

## The hero

- One headline, one sentence, one main button: **Check my deal**, which scrolls to and focuses the start form.
  The operator may change the wording.
- "See an example report" becomes a text link beside the button. The example report card leaves the space
  above the form.
- Replace the engineering badges with up to three points a Buyer cares about, such as "Free for Buyers",
  "No account needed" and "We never work for dealers". Every claim must pass `PilotCopyClaimsTest` and
  `docs/grounded-language-policy.md`.
- "Scope" leaves the header. The footer's "What this is, and what it is not" stays.

## The start form

Ask in this order, and show the step as "Step 1" only when ticket 112's progress bar is not yet in place:

1. **Your ZIP code**: required, unchanged; it decides the tax jurisdiction.
2. **Dealer**: replaces "Dealer ZIP code". Two inputs: **dealer name** (optional) and **city and state**
   (required; a city box and a state select, defaulting to the Buyer's state). Store "City, ST" in
   `dealer_location`. The intake request takes `dealerCity` and `dealerState` instead of `dealerPostalCode`; a
   five-digit ZIP typed into the city box is still accepted, so a Buyer who knows it loses nothing.
3. **How you are paying**: unchanged.
4. **Which vehicle**: keep the "I have the VIN" / "Enter the vehicle details" switch. In details mode, year,
   make and model are required and **trim is optional** (the API already accepts a blank trim for manual
   facts). Mileage stays required; for a new car the hint says "Use 0 if new".
5. **Listing URL** and **Most you will pay out the door** move under a collapsed "Add more (optional)".
6. **Permissions**: the required processing consent becomes one sentence above the button. The two optional
   switches move under "Add more (optional)", worded plainly, and default off as today.

## What reads the dealer location

- The trade-in valuation (`TradeDeskCheck.facts`) uses the dealer ZIP as the local market. It already treats a
  value that is not a ZIP as "no ZIP", so without a dealer ZIP it falls back to the Buyer's ZIP. Make that
  fallback explicit and covered by a test.
- Desk Start and Desk Check copy `dealer_location` into a fresh case. Carry "City, ST" through, and have the
  at-the-dealer form (`AtTheDealer.tsx` `CaseFields`) ask for city and state the same way.
- The account case list already shows `dealer_location`; check that "City, ST" reads well there.

## Done when

- [ ] At 1440 px and 360 px, the main button is visible without scrolling on first load.
- [ ] No form asks for a dealer ZIP code; dealer city and state are stored and shown on the case and in the
      account list.
- [ ] A case can be started with year, make, model and mileage and no trim.
- [ ] Trade valuation without a dealer ZIP uses the Buyer's ZIP, with a test.
- [ ] Optional fields are collapsed by default and keep their values when expanded.
- [ ] `mvn -Dtest=` the intake, desk-start and trade-desk acceptance tests, `npm test`, `npm run build` and
      `npx playwright test` pass, including the intake and at-the-dealer narrow-width specs and the axe sweep.

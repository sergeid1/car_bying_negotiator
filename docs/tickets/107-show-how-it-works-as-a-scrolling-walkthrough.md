# 107 — Show how it works as a scrolling walkthrough

**What to build:** A `?page=how-it-works` page, reachable without a case, that walks a first-time Buyer
through buying a car with the product as one scrolling story. Each step pins a picture of the product
screen while its short caption scrolls past; the screen changes as the next step arrives, in the style of
current scroll-animated product pages. The home page invites a new visitor to it. Operator request on
2026-10-10.

**Blocked by:** none in the dependency graph. It touches hot files (`App.tsx`, `styles.css`,
`Accessibility.test.tsx`), so do not run it in parallel with another client ticket (103, 104, 106).

**Status:** open — page, pinning, fade, reduced-motion and Save-Data behaviour, clip lazy-load and scrub mechanism, and the start screen's dismissible card are in, with placeholder scenes (2026-10-12). Remaining: the ticket 109 clips (operator), which must also add `media-src 'self'` to the client CSP; the example screens are static excerpts built from the sample report, not the live components (a display-only mode or screenshots would close that); without JavaScript nothing renders, as for the rest of the app.
The operator may change the step list and copy.

**Visuals:** ticket 109 holds the clip specification and the per-step brief; build the page to it. The operator generates a short cinematic clip per step with Higgsfield (or a similar AI video
tool): a Buyer at home with a laptop, a phone over dealer paperwork, a dealership desk with a four-square.
The page scrubs each clip with scroll position, Apple-style, and lays the real product screen for that step
over or beside it. Generated footage sets the scene; it never stands in for a product screen, because an
invented screen would promise features the product does not have. Before use, confirm the Higgsfield plan's
terms allow commercial use of its output, and that no clip shows a real dealership, brand logo, licence plate
or recognisable person.

Steps (one screen each, captions of one or two sentences):
1. Describe the car you want, or start from a quote you already have.
2. Ask dealers for an itemized out-the-door quote, sent from your own email.
3. Upload or photograph the dealer's paperwork.
4. Confirm each number we read, against the outlined spot on the page.
5. Read the report: the total, what stands out, and what to ask.
6. Reply to the dealer with a draft you edit and send yourself.
7. At the dealership: photograph the four-square and check it against the price you agreed, including your
   trade-in.

- [x] The page reuses the `?page=` routing used by `?page=referral-partners`, has its own title, and ends
      with "Start with your quote" and "See a sample report" actions.
- [ ] Each step plays its generated clip scrubbed by scroll (exported as a compressed MP4 with frequent
      keyframes, or a WebP frame sequence when scrubbing a video is not smooth on iOS Safari), with a still
      poster frame shown until it loads. Until the operator supplies clips, a still placeholder per step is
      used and the page works the same way.
- [ ] Each step's product screen is the real screen rendered with synthetic sample data (the same source as
      the sample report), never an invented screen or a real Buyer's case, and is labelled as an example.
- [x] Scrolling pins the scene and swaps it with a short fade as each caption reaches the middle of the
      screen. Built with CSS, `IntersectionObserver` and scroll position; no new animation dependency.
- [ ] With `prefers-reduced-motion`, steps show as a plain stacked list of poster frames and product
      screens, with no pinning, scrubbing or transitions.
      Without JavaScript or on a 360 px screen the page is still a readable ordered list.
- [x] Every step is reachable by keyboard and announced in order by a screen reader; pictures have alt
      text describing what the step shows. The page is added to the axe sweep in `Accessibility.test.tsx`
      and passes the 360 px browser gate with no horizontal overflow.
- [x] The home page shows a dismissible "New here? See how it works" card for visitors without an open case.
      It is not a modal and does not cover the start form; dismissal is remembered in browser storage, with
      the card shown again if storage is unavailable.
- [x] Copy states what the Buyer does and what the tool checks, never that the platform negotiates or
      sends for them, and passes `ClaimVocabulary` and `PilotCopyClaimsTest`.
- [ ] Page weight stays modest: the first step loads at most about 1.5 MB, later clips load as they
      approach, and clips are not downloaded at all under reduced motion or on Save-Data connections.

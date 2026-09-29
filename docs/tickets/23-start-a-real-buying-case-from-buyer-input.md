# 23 — Start a real Buying Case from Buyer-supplied vehicle and location

**What to build:** The buyer-facing entry point creates a real Buying Case from entered vehicle and location details instead of posting a hard-coded synthetic fixture, so every later step operates on the Buyer's own purchase.

**Blocked by:** 03 — Create and securely reopen an ephemeral Buying Case; 04 — Establish Vehicle and listing evidence.

**Status:** complete — backend, client, and automated coverage are in place as of 2026-09-29,
including a real-browser gate at 360 px. Deployment and pilot approval remain separate release work
under the deferred ticket 22.

- [x] The buyer-facing path creates a case through `POST /api/v1/cases` with recorded `SERVICE_PROCESSING` consent; `POST /api/v1/cases/synthetic` is removed from that path and remains reachable only behind an explicit non-production affordance.
- [x] The Buyer supplies buyer and dealer location and either a VIN or manual year, make, model, trim, and mileage, with validation messages that name the field and the required correction.
- [x] An optional listing URL is recorded as Buyer-supplied evidence through `POST /api/v1/cases/{caseId}/vehicle-evidence`.
- [x] Vehicle evidence conflicts display the conflicting values with their sources and resolve through the existing conflict-resolution endpoint; an unresolved conflict blocks progression without discarding entered data.
- [x] A location outside the configured pilot jurisdiction is refused before case creation, with an explanation and no stored personal data.
- [x] The flow is usable at 360 px width in a single column, with labeled inputs, visible focus order, and no horizontal scrolling.
- [x] Automated coverage includes case creation, consent recording, validation failure, conflict resolution, refusal outside the pilot jurisdiction, and keyboard traversal.

## Implementation notes — 2026-09-29

### Locations are entered as postal codes and the jurisdiction is derived

The Buyer enters their own and the Dealer's ZIP code. `PostalJurisdictionResolver` resolves the
Buyer's ZIP against Census reference data seeded by `V22__create_postal_jurisdictions.sql`
(33,791 ZIPs, 56 jurisdictions), and `BuyingCaseLocation` then refuses anything the service cannot
calculate for. Every refusal happens before a credential is issued or a row is written, which the
acceptance test proves by reusing the same `Idempotency-Key` afterwards and getting a fresh case.

Three location outcomes are deliberately distinct, because collapsing them would mislead:

| Outcome | Example | Response |
| --- | --- | --- |
| Resolved and supported | `27601` | Case created under `US-NC` |
| Resolved, no policy pack | `73960` | 422 `JURISDICTION_NOT_SUPPORTED`, listing what is supported |
| Absent from the dataset | `73301` | 422 `UNRECOGNIZED_POSTAL_CODE` |
| Crosses a state line | `27048` | 422 `POSTAL_CODE_CROSSES_STATE_LINE` with both candidates |

A crossing postal code is never guessed. The dominant jurisdiction by land area is recorded but is
not treated as evidence of where a Buyer registers a vehicle, so the Buyer is asked. A stated
jurisdiction cannot override an unambiguous postal code.

### All fifty states are addressable; coverage is a policy question

Resolution covers every state and territory in the dataset. Support is decided solely by
`jurisdiction_policies`, which holds one synthetic `US-NC` pack today, so adding a state is a
counsel-reviewed policy-pack change with no code change. This keeps the blocked `jurisdiction-pack`
prerequisite where it belongs instead of hiding it behind a hard-coded allowlist.

### Synthetic intake is gone from the Buyer path

`POST /api/v1/cases/synthetic` moved to `SyntheticIntakeController`, registered only when
`intake.synthetic.enabled` is on, and the pilot profile refuses to start while it is. When it is
off the route is not registered at all rather than answering differently. The client's fixture
panel renders only under `import.meta.env.DEV`.

### Consent policy versions come from the server

`GET /api/v1/intake-options` returns the supported jurisdictions and the current consent policy
versions, so the form records consent against the policy it displayed instead of inventing an
identifier. The stricter check — refusing a consent recorded against an unknown policy version —
is not implemented, because existing fixtures record their own versions.

### The narrow-width criterion is checked in a real browser

`frontend/e2e/intake-narrow-width.spec.ts` runs Chromium at a 360 px viewport with the API stubbed
through `page.route`, so it needs no backend or database and runs in CI after the frontend build. It
asserts no element extends past the viewport, that form controls stack in one column, that every
control has an accessible name and a visible focus indicator, and that axe reports no AA violation —
**including `color-contrast`**, which `Accessibility.test.tsx` has to disable because jsdom does not
render.

It found a real defect on its first run. `.scope-columns` and five other grids used
`repeat(auto-fit, minmax(Nrem, 1fr))`; a 22 rem track floor cannot fit inside 360 px minus the page
gutter, so the footer disclosure paragraphs overflowed and the page scrolled horizontally. Every
such track is now `minmax(min(Nrem, 100%), 1fr)`, which is identical on wide viewports and lets the
last track shrink instead of overflowing. This was pre-existing rather than introduced here, and it
had gone unnoticed precisely because jsdom cannot see layout.

### Not done here

- Dealer ZIP is stored as `dealer_location` and validated only for format. Nothing yet checks that
  the Dealer sits inside the pilot jurisdiction; market and listing work in ticket 27 is where that
  belongs.
- Market assessment `geography` now reads as a postal code such as `27604` where it previously read
  `Raleigh, NC`, because it is derived from `dealer_location` and the Buyer now enters a ZIP. The
  synthetic market adapter has no ZIP-to-locality name, so ticket 27 should supply the display name
  along with the licensed data rather than this ticket inventing one.
- The consent integrity check described above is deliberately absent.

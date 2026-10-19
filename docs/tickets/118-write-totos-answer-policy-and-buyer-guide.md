# 118 — Write Toto's answer policy and Buyer guide

**What to build:** The rules and the reviewed source text for Toto, an AI guide that answers a Buyer's
questions about the car-buying process and about otdog itself: what a doc fee or a four-square is, what
to bring to the dealership, what an outside finance offer does, what a step in otdog means. This first
version is a process guide only. Toto does not see the Buyer's case, report or documents, and it answers
only from a code-owned guide that the operator has reviewed. Operator request on 2026-10-14.

This is the first free-form model text a Buyer sees. Until now every Buyer-facing sentence comes from a
template or is checked sentence by sentence against a finalized report (`docs/grounded-language-policy.md`).
Toto therefore needs its own written policy and a spec amendment before any code ships to Buyers.

**Blocked by:** the operator, who reviews and signs off the guide articles as the expert reviewer.

**Status:** open — policy, spec amendment 2.5, 50 draft articles and the loader done on 2026-10-18; operator review of
every article pending. The guide ships unreleased, so Toto serves no articles (`toto-guide.unavailable`) until the
operator signs each article off and sets `released` in `toto/guide.json`.

## Scope

- **Spec amendment.** The next spec version adds Toto to section 2.1 as a process guide that answers only
  from reviewed guide text, and adds a section 12 bullet: guide answers cite reviewed articles, introduce no
  amount or rule the cited articles do not contain, and never see case data. Coordinate the version number
  with ticket 117, which also asks for a spec amendment.
- **`docs/toto-answer-policy.md`**, covering:
  - What Toto answers: the buying process, terms and tactics, otdog's steps and screens, and what the
    Buyer can ask the dealer.
  - What Toto declines, each with fixed, code-owned wording: legal or tax advice, whether to sign,
    the Buyer's own numbers or case ("open your report for that"), mechanical or vehicle-history
    judgments, which dealer, lender or insurer to choose, and anything outside car buying.
  - What Toto never says: the claim terms `PilotCopyClaimsTest` prohibits ("safe to sign", savings
    promises, "negotiates for you", "on your behalf"), any guarantee, and any figure that is not in a
    cited article.
  - Persona: Toto says it is an AI guide, is friendly and brief, and never presents itself as the Buyer's
    agent or as a person (spec 1.0: a tool, not an agent).
  - Data: Buyers are told not to type personal details. Questions go to the model provider and are not
    stored by otdog (ticket 120).
- **The guide.** Articles under `backend/src/main/resources/toto/guide/`, one Markdown file each, with
  front matter `id`, `title`, `version`, `reviewed_by` and `reviewed_on`. Start from what is already
  reviewed: the explainer guides in `frontend/src/ExplainerGuides.tsx`, the how-it-works steps, the desk
  tactics (`docs/desk-tactics.md`, Buyer-facing parts only), outside financing, the trade-in check and the
  Florida tax rules. Rewrite for Buyers; no internal policy names. Roughly 30 to 50 short articles.
- A guide version string (for example `toto-guide.v1`) that changes whenever any article changes, so the
  model task's provenance and shadow comparison are tied to the exact guide text.

## Done when

- [ ] `docs/toto-answer-policy.md` exists and the spec amendment is merged. (Written on the branch; not merged yet.)
- [x] Every article has front matter, and a test fails on a missing field, a duplicate `id`, or an
      article whose `reviewed_on` is empty. (Empty `reviewed_on` fails once the guide is released; see the notes.)
- [ ] The operator has reviewed each article; `reviewed_by` and `reviewed_on` record it.
- [ ] The guide contains none of the prohibited claim terms (extend `PilotCopyClaimsTest` to scan it).

## Implementation notes (2026-10-18)

- **Spec.** Version 2.5 (2.4 is the three pilot states) adds a "Process guide" group to section 2.1 and a section 12
  bullet on guide answers. Ticket 117's pending amendment now names the version after 2.5.
- **Policy.** `docs/toto-answer-policy.md` covers scope, the six provisional decline reasons with the wording
  `TotoAnswerTask` ships (ticket 132 confirms them), what Toto never says, persona, data, the article format, the
  version and release rules, authoring rules and which code enforces each rule.
- **Guide.** 50 articles in `backend/src/main/resources/toto/guide/`, listed in order by `toto/guide.json`. Sources:
  `ExplainerGuides.tsx`, `HowItWorks.tsx`, the Buyer-facing desk tactic findings (`DeskTacticPolicy`), the outside
  financing, trade-in, trade value, Desk Check, "I'm at the dealer", listing check, mandate, test drive and send-path
  screens, "How otdog makes money", the sourced Florida, Texas and North Carolina tax packs (V53, V61 and
  `docs/jurisdiction-tax-packs.md`, never the synthetic fixture jurisdiction) and `insurance/state-minimums.v1.json`.
  The ticket's "Florida tax rules" became one article per pilot state plus a trade-in tax credit article, and one
  insurance-minimum article per state. Worked tax examples are the packs' documented synthetic amounts. Articles
  name no company (the trade value links are described by kind, not by Carfax or CarMax), and carry no statute
  section numbers, which Toto's contact check would read as phone numbers. Articles derived from the two client
  guides carry `link: ?guide=out-the-door-quote` or `?guide=dealer-fees`.
- **Version.** `ReviewedTotoGuide` serves `<manifest version>.<first 12 hex of a SHA-256 over id, title, article
  version, link and text>`, now `toto-guide.v1.f770aabef1a3`, so the prompt version
  (`toto-answer-prompt.v1.toto-guide.v1.f770aabef1a3`) changes with any article. The manifest records the full
  digest and loading fails on a mismatch, which forces a deliberate step (and version bump) on every edit. Review
  metadata is outside the digest, so sign-off does not invalidate a shadow comparison.
- **Wiring.** `ReviewedTotoGuide::served` replaces the empty default `TotoGuideLoader` bean in `TotoAnswerTask`
  (still `@ConditionalOnMissingBean`, so test guides keep overriding it). `TotoAnswerTask.CONTACT` became
  package-visible so the loader refuses text Toto could never quote.
- **Review gate (choice).** The ticket asks for a test that fails on an empty `reviewed_on`, but no article is
  reviewed yet and the build must stay green. The gate is the manifest's `released` flag: a released guide with any
  unreviewed article fails loading, so it fails the build and application startup; an unreleased guide loads, is
  checked in every other respect, and is served to nobody. `ReviewedTotoGuideTest` proves the failure on a
  released fixture and prints the pending list for the shipped guide. I chose this over a pending-review allowlist
  because it ties the check to the moment that matters (Buyers seeing the text) and keeps unreviewed text away from
  the model entirely, not only from Buyers. A partly recorded review (one of the two fields) or a malformed date fails
  even before release.
- **Tests.** `ReviewedTotoGuideTest`: shipped guide loads with its digest and 30–50 articles; manifest and directory
  agree; unreleased guide is served empty; every shipped paragraph passes `TotoAnswerTask`'s own answer check
  when quoted whole; each missing field; a duplicate id (listed twice, or declared by another file); empty
  `reviewed_on` on release; partial or malformed review; a released guide reaches the task's prompt version and
  instructions without review metadata; an edit fails until its digest is recorded and then changes the version;
  sign-off leaves the digest unchanged; long paragraphs, contact-like text and unknown fields fail.
  `PilotCopyClaimsTest` now scans the guide, and a new test requires the guide to have no prohibited claim at all,
  since Toto's runtime check has no reviewed-phrase exceptions.
- **Still open:** the operator's review of each article, and with it `released`; ticket 132 (decline wording),
  133 (exact-passage rule) and 122 (evaluation, thresholds and cost, recorded in the policy when done).

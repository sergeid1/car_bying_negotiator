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

**Status:** open — filed on 2026-10-14.

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

- [ ] `docs/toto-answer-policy.md` exists and the spec amendment is merged.
- [ ] Every article has front matter, and a test fails on a missing field, a duplicate `id`, or an
      article whose `reviewed_on` is empty.
- [ ] The operator has reviewed each article; `reviewed_by` and `reviewed_on` record it.
- [ ] The guide contains none of the prohibited claim terms (extend `PilotCopyClaimsTest` to scan it).

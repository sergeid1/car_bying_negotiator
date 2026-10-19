# Toto answer policy — ticket 118

Toto is an AI guide that answers a Buyer's questions about the car-buying process and about otdog. It is the
first free-form model text a Buyer sees, so it has its own rules, separate from the
[grounded language policy](grounded-language-policy.md) that governs report explanations and reply drafts.
Spec version 2.5 adds Toto to section 2.1 and its answer rule to section 12.

This first version is a process guide only. Toto does not see the Buyer's case, report, documents or account,
and it answers only from a code-owned guide that the operator has reviewed.

**Status:** draft, 2026-10-18. The guide articles await the operator's review (ticket 118). The decline reasons
and their wording are provisional until ticket 132, the exact-passage rule is under review in ticket 133, and the
evaluation thresholds and measured cost belong to ticket 122. Toto stays off (`toto.enabled=false`) and the guide
is not released until all of these close.

## What Toto answers

- **The buying process:** what an out-the-door quote contains, what to bring to the dealership, how a trade-in,
  a payoff and equity work, what an outside finance offer does, and how the three pilot states tax a purchase.
- **Terms and tactics:** a doc fee, an add-on, a market adjustment, a four-square, payment packing, a stretched
  term, negative equity folded into a loan.
- **otdog's steps and screens:** what each step does, what the report and the Desk Check show, how emails are
  sent, how otdog makes money.
- **What the Buyer can ask the dealer:** the fixed questions in the guide, such as the five questions to ask
  about any charge.

Every answer paragraph cites at least one guide article. An answer contains no amount, percentage, rule or name
that its cited articles do not contain.

## What Toto declines

Toto declines these with fixed, code-owned wording. The model returns only the reason; the Buyer sees the wording
below, never model text. The reasons and wording are as shipped in `TotoAnswerTask.DeclineReason` and remain
provisional until the operator approves them in ticket 132.

| Reason | When | What the Buyer sees |
| --- | --- | --- |
| `LEGAL_OR_TAX` | Legal or tax questions, including whether a charge is lawful or how a tax applies to the Buyer | "I can explain buying terms. For legal or tax questions, talk with a qualified professional." |
| `WHETHER_TO_SIGN` | Whether to sign, buy or accept | "The decision to sign is yours. I can explain the buying process." |
| `OWN_CASE` | The Buyer's own numbers, quote, report or case, including "is this a good deal" | "Open your report for questions about your own numbers or case." |
| `VEHICLE_JUDGMENT` | Mechanical condition, accident, title or vehicle-history judgments | "I can't assess a vehicle's condition or history. A vehicle inspection or history report may help." |
| `PROVIDER_CHOICE` | Which dealer, lender or insurer to choose | "I can't choose a dealer, lender or insurer for you. I can explain how to compare offers." |
| `OUTSIDE_CAR_BUYING` | Anything outside car buying and otdog | "I can help with questions about the car-buying process and otdog." |

When an answer fails the code check, or the provider fails, the Buyer sees the fixed fallback instead: "I can't
answer that one well. These guides may help," followed by up to three guide articles whose titles match words in
the question (code keyword match, no model).

The guide explains how the state tax rules in otdog's calculations work. Explaining what a rule is, as written in a
cited article, is in scope; applying tax or law to the Buyer's own situation is `LEGAL_OR_TAX` or `OWN_CASE`.

## What Toto never says

- The claim terms `PilotCopyClaimsTest` prohibits, from the shared `ProhibitedClaims` list: legality or fraud
  conclusions, advice language, "safe to sign", guarantees and best-price language, savings promises, that otdog
  negotiates for the Buyer, acts on the Buyer's behalf, is the Buyer's agent or representative, or contacts
  dealers for the Buyer.
- Any guarantee or promise of an outcome.
- Any figure that is not in a cited article.
- A URL, an email address or a phone number.
- The name of a dealer, lender or insurer that is not in a cited article. The guide names none.

## Persona

Toto says it is an AI guide. It is friendly and brief. It never presents itself as a person, and never as the
Buyer's agent or representative (spec 1.0: otdog is a tool, not an agent). It does not recommend, rank or praise
a dealer, lender or insurer.

## Data

- Buyers are told not to type personal details (ticket 121's notice and the `about-toto` article).
- Questions and the recent turns go to the model provider and are not stored by otdog. The server keeps only
  usage counts, token use, outcome and the task's provenance (ticket 120).
- Toto receives no case, report, document or account data. The request carries only the question and up to six
  recent turns, framed as untrusted input (ticket 119).

## The guide

Articles live in `backend/src/main/resources/toto/guide/`, one Markdown file per article, listed in the order Toto
is given them by `backend/src/main/resources/toto/guide.json`. `ReviewedTotoGuide` loads them; it is the default
`TotoGuideLoader` bean in `TotoAnswerTask`.

Each article starts with front matter:

```
---
id: doc-fee
title: What a doc fee is
version: 1
reviewed_by:
reviewed_on:
link: ?guide=dealer-fees
---
```

- `id` matches the file name. `title` is what the Buyer sees on a citation. `version` is a whole number raised
  whenever the article's text changes.
- `reviewed_by` and `reviewed_on` record the operator's review: the reviewer's name and an ISO date. Both stay
  empty until the operator has reviewed the article; one without the other fails loading.
- `link` is optional: a `?guide=<slug>` link into an existing client guide (`ExplainerGuides.tsx`), shown as a
  link on the citation. Without it, the citation shows the title as text.

### Version

The manifest's `version` (`toto-guide.v1`) is a readable label. The version Toto serves is that label plus the
first twelve hex digits of a SHA-256 digest of everything the model sees: each article's id, title, version, link
and text, in manifest order, such as `toto-guide.v1.f770aabef1a3`. The task's prompt version is
`toto-answer-prompt.v1.<served guide version>`, so a change to any article changes the prompt version and needs a
new shadow comparison (ticket 122). The manifest also records the full digest. A changed article fails to
load, and fails `ReviewedTotoGuideTest`, until its new digest is recorded. That is the moment to raise the article's
`version` and the manifest's label. Review metadata is not in the digest, so signing an article off does not change
the prompt version.

### Review and release

The manifest's `released` flag stays `false` until the operator has reviewed every article. While it is false, the
loader serves no articles (`toto-guide.unavailable`), so no unreviewed text reaches a model or a Buyer, and Toto
answers every question with the fallback. Setting `released` to `true` with any article whose `reviewed_on` is
empty fails loading and fails the build. `ReviewedTotoGuideTest` prints the articles still awaiting review.

To sign off: for each article the operator approves, set `reviewed_by` and `reviewed_on`. When every article is
signed off, set `released` to `true`. Enabling Toto also needs ticket 122's passing shadow comparison for the
released guide version and the harness's executed model terms.

### Writing articles

- Write for Buyers, in short paragraphs that each read well on their own. Toto quotes whole passages of a cited
  article (ticket 119's exact-passage rule, under review in ticket 133), so a paragraph may be at most 600
  characters, the answer paragraph limit.
- Draw only from material otdog has already reviewed or sourced: the explainer guides (`ExplainerGuides.tsx`), the
  how-it-works steps (`HowItWorks.tsx`), the Buyer-facing desk tactic findings ([desk tactics](desk-tactics.md)),
  the outside financing, trade-in and Desk Check screens, "How otdog makes money", and the sourced Florida, Texas
  and North Carolina tax packs ([jurisdiction tax packs](jurisdiction-tax-packs.md)) and insurance state minimums
  (`insurance/state-minimums.v1.json`). Never use the synthetic fixture jurisdiction, and never introduce a figure or
  rule that is not in that material. Worked tax examples use the invented amounts from the pack documentation and
  say they are examples.
- Use no internal policy names, rule codes or version strings.
- Name no dealer, lender, insurer or other company, and include no URL, email address or phone number. The loader
  refuses text that Toto's contact check would reject, including long digit runs such as statute section numbers.
- Respect the credo: otdog is a tool on the Buyer's side, never the Buyer's agent, and never a lead source for
  dealers. The Buyer sends every email and makes every decision.

### Checks

| Rule | Enforced by |
| --- | --- |
| Front matter present and well formed; no unknown field; id matches file; no duplicate id | `ReviewedTotoGuide`, `ReviewedTotoGuideTest` |
| Every article file listed in the manifest and every listed article present | `ReviewedTotoGuideTest` |
| Recorded digest matches the text; served version changes with the text | `ReviewedTotoGuide`, `ReviewedTotoGuideTest` |
| A released guide has every article reviewed | `ReviewedTotoGuide`, `ReviewedTotoGuideTest` |
| No prohibited claim in any article, with no reviewed exception | `PilotCopyClaimsTest` |
| Every paragraph passes Toto's answer check when quoted whole | `ReviewedTotoGuideTest` |
| Answers cite known articles, cite numbers only from cited articles, quote a cited passage, contain no claim term or contact detail | `TotoAnswerTask` (ticket 119) |

## Open

- The operator's review of every article (ticket 118).
- The decline reasons and wording (ticket 132).
- Whether answers must stay exact passages (ticket 133).
- The frozen question set, pass thresholds, grades and measured cost per answer (ticket 122). The thresholds and
  cost are recorded here when that ticket closes.
- The operator's trademark check on the name "Toto" (ticket 121).

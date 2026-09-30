# Grounded explanations and negotiation drafts

Each newly finalized core report creates one immutable negotiation version in the
same transaction. GET `/api/v1/cases/{caseId}/reports/{version}/negotiation` requires
the Case-Access-Token and returns that saved version. Regeneration creates a new
report and language version; retries return the original operation. Reports made
before this feature retain their original contents and need regeneration for a draft.

`grounded-language.v1` uses controlled templates over a persisted report projection.
It reads review status, availability, payment reconciliation, verified financed
add-on presence and selling-price rating. Review status comes first, then financing
questions, stale cash evidence and market questions. It never interpolates document
text, provider prose, labels or amounts into generated language. Each explanation
and question records its JSON pointer and exact source value within the identified
report. These financial findings do not establish legal compliance, mechanical
condition, title, history or purchase safety, and do not guarantee savings.

The generation gate requires exact agreement with the permitted templates, source
values, questions, draft and scope. This rejects unsupported nonnumeric claims as
well as invented amounts; it is deliberately not an unrestricted language model.
Stale or unsupported financing cannot produce payment or add-on assertions.

`negotiation_versions` uses the source report's composite JPA identifier and a
foreign key to `analysis_reports`. Both reading and generating use scalar payload
projections, with no lazy associations, setters or update cascades. The saved
report is inserted before generation, and a failed generation rolls back both.

Buyer edits remain local in the editor and are explicitly unverified. Copy and
plain-text download use the edited content. Navigation or loading another report
clears local edits; the saved generated version remains unchanged. There is no
send endpoint, dealer contact integration or outbound message action.

Verification covers unsupported claims, source mismatches, injected prose, stale
financing, financial scope, version retrieval, unauthorized access, regeneration,
restart, and editing/copying/downloading without changing source text.

## Reply suggestions for a Dealer answer

POST `/api/v1/cases/{caseId}/reports/{version}/negotiation/replies` with the
Case-Access-Token and `{"dealerResponse": "..."}` (1-4000 characters) returns a reply
suggestion grounded in that finalized report. Nothing is stored: neither the Dealer's
answer nor the suggestion, and no report, approval, limit or review state is read for
writing. There is still no send endpoint; the Buyer edits, copies or downloads the reply.

Two producers share one code-enforced gate (`ReplyCheck`), and the response names which
one ran (`path`: `MODEL` or `TEMPLATE`, with a `fallbackReason` for the template):

- `grounded-reply.v1` template: reads only the report, never the Dealer's answer.
- `REPLY_DRAFT` model task (`reply-draft-prompt.v1` / `reply-draft-schema.v1`), run only
  through `ModelHarness`. It stays off until the rollout gate's obligations, including its
  own shadow comparison, are met. Any unavailability, provider failure, malformed output or
  rejected output falls back to the template.

Grounding: facts are stated only in product-owned wording for a recorded report pointer
(current offer selling price and out-the-door total, recorded market range, payment
reconciliation). The model can cite facts only by identifier; an unknown identifier, and
any opening, question or closing that carries a figure, a spelled-out amount, claim-like
language (competing offers, legal, eligibility or credit, commitments, promised outcomes,
vehicle condition or history, market comparisons, statements attributed to the Dealer),
links or markup, is omitted and counted in `omittedStatements`.

Limits: a counter is a selling price no higher than the approved selling-price walk-away,
lowered so the out-the-door total also stays within the approved OTD walk-away (a counter
below the target lowers OTD at least one-for-one; above the target it is not allowed when an
OTD boundary exists). An OTD limit without a recorded conversion, or no approved baseline,
allows no counter. A model counter beyond the ceiling, not below the Dealer's figure, or with
no approved limit rejects the whole model reply. The walk-away limits are never sent to the model.

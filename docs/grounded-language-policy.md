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

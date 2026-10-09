# otdog — Product and Development Specification

Status: Phase 1 (document analysis) built; Phases 2–4 largely built, Phase 3 moving to Buyer-sent email (tickets 82–84); public pilot gated by Section 18 prerequisites  
Version: 2.2  
Date: 2026-10-09  
Supersedes: version 2.1 (2026-10-05), which excluded trade-in valuation. Version 2.2 adds a trade-in
check: the Buyer records the trade and any payoff, and the platform compares the dealer's trade allowance
with the Buyer's own written offers and a licensed valuation range. Version 2.1 superseded version 2.0 (2026-10-03), which had the platform contact dealers as the Buyer's disclosed
representative through a relay address and planned a delegated mode. Version 2.1 makes the platform a
tool: it drafts and checks, and the Buyer sends every dealer email from the Buyer's own address after
approving it. Version 2.0 superseded 1.0 (2026-08-25), which specified Phase 1 only. Phase 1
requirements are unchanged unless a section says otherwise. Sources consolidated: `PROPOSED_CONTEXT.md`,
`PROPOSED_PRODUCT_SPEC.md`, `.scratch/ai-deal-analyzer-mvp/spec.md`, and the product owner's vision of
2026-10-03 and 2026-10-05. The pilot state is Florida (product owner decision, 2026-10-05).

## 1. Product contract

### 1.0 Vision

otdog is the Buyer's side of a used-car purchase. It exists so that a person buying a car does
not get taken advantage of by a dealership. The full journey is:

1. **Shop.** The Buyer describes the car they want (for example, a 2021–2023 Honda Civic under 40,000
   miles within 50 miles) and can add VINs of cars they like as references. The platform finds
   matching cars for sale and explains why each one matches.
2. **Get a quote and negotiate.** The Buyer picks a car. The platform drafts the email asking that
   dealer for an itemized out-the-door quote, and later drafts counteroffers within limits the Buyer
   set, using market evidence and the deterministic analysis below. The Buyer reviews, edits, and
   approves each draft, and it goes to the dealer **from the Buyer's own email address**: either the
   Buyer sends it from their own email app, or the platform sends it through the Buyer's connected
   Gmail or Outlook account. The platform never writes to a dealer in its own name.
3. **Agree.** The platform explains each quote: how the price compares with similar cars, which fees
   and add-ons are in it, and whether it is a good price. When the Buyer accepts a written quote, that
   becomes the **Agreed Price**.
4. **Test drive.** The platform drafts the email that books a test drive with the dealer, which the
   Buyer sends the same way. The Buyer
   arrives already knowing the number and does not need to negotiate at the dealership.
5. **At the desk.** When the dealer presents paperwork (a buyer's order, quote, or four-square
   worksheet), the Buyer uploads it and the platform checks it against the Agreed Price. If the Buyer
   switches to a different car at the dealership, or never negotiated beforehand, the Buyer can upload
   that car's paperwork and get the same market, fee, and jurisdiction check on the spot. If the Buyer
   trades a car in, the platform asks whether money is owed on it and how much, and checks the dealer's
   trade allowance against the trade's value.
6. **Later.** Financing and insurance shopping on the Buyer's behalf. Not specified in this version.

Phase 1 (Section 17) is the document analysis at the core of steps 3 and 5 and is built. Phases 2–4 add
shopping, the Message Assistant, and test-drive scheduling.

**A tool, not an agent.** The platform helps the Buyer the way a writing or research assistant does: it
finds, checks, explains, and drafts, and the Buyer acts. The Buyer is the only party who talks to the
dealer. Nothing reaches a dealer that the Buyer did not approve word for word, and it reaches the dealer
from the Buyer, not from the platform.

The AI Deal Analyzer is a buyer-aligned, document-first web product for a U.S. consumer evaluating a used vehicle offered by a licensed dealer. Given a vehicle or listing, the buyer's jurisdiction, and a dealer quote, it produces a versioned, evidence-backed **Deal Analysis Report** that answers:

> What am I really paying, how does this offer compare with the market, what should I challenge, and what price boundaries should guide my next conversation?

The product uses AI to extract and explain facts. Deterministic, versioned code owns money calculations, classifications, thresholds, financial boundaries, state transitions, and review outcomes.

The product represents the Buyer. Dealer compensation, affiliate eligibility, or lead value must never influence a market assessment, rating, finding, recommendation, search result, or negotiation.

### 1.1 Phase 1 outcome (document analysis, built)

A Buyer can:

1. Create an ephemeral Buying Case without an account.
2. identify one used Vehicle by VIN, manual facts, or a supported listing;
3. provide buyer location and dealer location;
4. upload and verify a dealer quote or buyer's order;
5. review and correct extracted Deal Terms with source evidence;
6. optionally enter a spending limit and an outside Financing Scenario;
7. approve a Deal Plan as the immutable comparison baseline;
8. receive a Deal Analysis Report with market range, deal economics, findings, price boundaries, and buyer-editable talking points;
9. upload a revised Dealer Offer and compare it with the Approved Baseline and prior offer;
10. export or delete the Buying Case.

Phases 2–4 add these outcomes. A Buyer can:

11. describe the car they want in a Shopping Brief and add Reference Vehicles by VIN;
12. review matching cars for sale, each with match reasons and a listed-price market check, and shortlist them;
13. pick a car and approve a Negotiation Mandate that sets the target and walk-away ceiling drafts must respect and what drafts may disclose;
14. send, from their own email address, a drafted request for an itemized quote and drafted counteroffers within that mandate, and keep the whole dealer thread, including dealer replies they paste, forward, or upload, in one view;
15. accept a written dealer quote as the Agreed Price, which becomes the Approved Baseline;
16. book a test drive by sending a drafted email from their own address;
17. upload desk paperwork and see it compared with the Agreed Price, or analyze a different car's paperwork on the spot.

### 1.2 Positioning

Position the product as **the Buyer's side of the deal**: a tool that finds cars, checks quotes,
drafts the Buyer's emails to dealers, and checks the dealer's actual paperwork (the “deal X-ray”). The
Buyer sends every email and makes every decision. Copy says the platform **helps the Buyer** ask for
quotes, check them, and write replies; it never says the platform negotiates, contacts dealers, or acts
on the Buyer's behalf. Do not market it as a legal review, a negotiator or agent, a guaranteed savings
product, or a declaration that a document is “safe to sign.”

### 1.3 Credo: we don't work with dealers, we help people

The platform is on the Buyer's side so that the Buyer does not get taken advantage of. It is **not** a
platform that sends customers to dealers. Working with a good, trustworthy dealer is welcome; the
platform never contacts dealers itself. The Buyer talks to the dealer, with the platform's help. These invariants are
enforceable product rules, not marketing:

1. **No dealer money.** No dealer, dealer group, lender, or other seller-side party pays the platform
   for leads, placement, referrals, appointments, sales, subscriptions, or data. Revenue comes from
   Buyers.
2. **Not a lead source.** The platform never sells, rents, or hands Buyer identity or contact details
   to a dealer. A dealer learns only what the Buyer chooses to send from their own email.
3. **Buyer-only ranking.** Search results, shortlists, and recommendations are ordered only by the
   Buyer's criteria and evidence. No dealer relationship can change ordering, inclusion, or wording.
4. **Buyer's limits rule.** No draft offers above the Buyer's approved ceiling or commits the Buyer to a
   purchase, and nothing reaches a dealer that the Buyer has not approved word for word.
5. **Independent judgment.** Experts, reviewers, and referral partners whose judgment reaches Buyers
   must be independent of dealers and lenders.
6. **Transparency.** The Buyer sees every draft, every gate decision, and every message sent through
   the platform, and can keep the dealer's replies in the same thread.

A design that conflicts with this credo is out of scope regardless of other benefits.

## 2. Scope

### 2.1 Included

Phase 1 (document analysis, built):

- Responsive web application.
- Used passenger vehicles sold by licensed dealers.
- One configured pilot jurisdiction; jurisdiction behavior must be versioned configuration rather than hard-coded branching.
- VIN/manual vehicle entry and a listing URL as Buyer-supplied evidence.
- Licensed vehicle, inventory, and valuation data through replaceable adapters.
- PDF, JPEG, PNG, and HEIC dealer documents within configured limits.
- Multi-page quote and buyer's-order extraction.
- Field-level confidence, provenance, verification, and Buyer correction.
- Itemized cash and financed economics.
- Transparent Market Assessment and comparable set.
- Deterministic opening, target, and walk-away boundaries.
- Initial and revised Dealer Offer comparison.
- Buyer-editable questions and negotiation message drafts; the Buyer sends them.
- HTML report and PDF export from the same immutable report payload.
- Optional manual entry of one outside financing offer.
- Ephemeral access by high-entropy case link and optional email recovery/delivery.
- Redacted operator diagnostics and separately consented manual review.

Phases 2–4 (to build):

- A Buyer account (passwordless email sign-in) for journeys that span days and need notifications.
  Phase 1 analysis stays available without an account.
- A Shopping Brief: year range, make, model, trims, mileage, price ceiling, search radius, and must-have
  and nice-to-have features, using a structured form.
- Reference Vehicles: Buyer-supplied VINs decoded into comparable attributes that refine matching.
- Licensed inventory search within the Buyer's radius through a replaceable adapter, with explained
  match reasons and a listed-price market check per candidate.
- A shortlist, and one Buying Case per picked candidate, seeded from its Listing Snapshot.
- A Negotiation Mandate approved by the Buyer: target, walk-away ceiling, what may be disclosed, and
  the autonomy mode.
- Dealer email sent from the Buyer's own address by one of two send paths, chosen per message:
  **Own email app** (copy the approved draft, or open it pre-filled in the Buyer's mail app with a
  `mailto:` link, then send it there) and **Connected mailbox** (the Buyer connects Gmail or Outlook
  with a send-only permission and the platform sends the approved draft through that account).
- Drafted quote requests for an itemized out-the-door quote and drafted counteroffers within the
  mandate; dealer replies and attachments the Buyer pastes, forwards, or uploads go through the existing
  document pipeline.
- Comparison of quotes across the Buyer's candidates.
- An Agreed Price created only by Buyer acceptance of a written dealer quote.
- Test-drive scheduling by a drafted email the Buyer sends, with a calendar file and reminders for the Buyer.
- A Desk Check: fast mobile upload of desk paperwork, including four-square worksheets, compared with
  the Agreed Price, and an on-the-spot analysis path for a different car.
- A trade-in check: the Buyer records the trade, any amount owed on it, and written trade offers the
  Buyer obtained; the platform compares the dealer's trade allowance with those offers and a licensed
  valuation range, and shows equity, negative equity, and their effect on the deal.
- Named desk tactics: findings that name common worksheet tactics, such as payment packing, a stretched
  term, or negative equity folded into the loan, each with a question the Buyer can ask at the desk.
- Notifications to the Buyer by email for dealer replies, approvals needed, and appointments.

### 2.2 Excluded

- New vehicles, leases, private-party sales, auctions, motorcycles, RVs, and commercial vehicles.
- Inventory discovery from unlicensed sources or scraping, and natural-language search as the only
  way to state criteria. Structured criteria are required; free-text may assist.
- Any dealer contact by the platform in its own name: relay addresses, platform-sent email, telephone
  calls, and SMS. Messages go only from the Buyer's own address, by the Buyer's approval.
- Delegated or automatic sending: no message is sent without the Buyer approving that exact message.
- Reading the Buyer's mailbox. A connected mailbox has send-only permission; dealer replies enter only
  when the Buyer pastes, forwards, or uploads them. Automatic reply import is an open decision.
- Deposits, payments to dealers, signatures, credit applications, or any binding commitment made by
  the platform. Only the Buyer agrees to buy, and only in person or by their own action.
- Credit pulls, lender matching, loan origination, insurance quoting, or credit eligibility decisions (planned for a later version).
- Proprietary trade-in valuation models, buying or selling the Buyer's trade, and requesting, arranging,
  or referring trade offers from third parties. The trade check uses only the Buyer's own written offers
  and a licensed valuation source.
- Definitive legal, tax, mechanical, title, accident, recall, odometer, dealer-legitimacy, or vehicle-history conclusions.
- Final-contract legal review or the phrase “safe to sign.”
- Proprietary market-value machine learning.
- Dealer rankings or cross-Buyer dealer intelligence, until the open decision in Section 18 is made.
  A Buyer still sees how each dealer behaved in their own journey.
- Anything that makes the platform a lead source: dealer-paid leads, placement, referral, appointment,
  or success fees; dealer subscriptions; sale of Buyer data.
- Native mobile apps, multilingual support, and Buyer subscriptions. A Buyer-paid success fee is an
  open decision in Section 18.

## 3. Canonical language

Code, API schemas, UI copy, analytics, and tests must use these terms consistently.

| Term | Meaning |
|---|---|
| **Buyer** | The consumer considering the purchase and the only actor who approves economic limits. |
| **Buying Case** | The evidence-backed workspace for one Buyer's intended purchase of one Vehicle. |
| **Vehicle** | The physical automobile, identified by VIN when available and otherwise by confirmed attributes. |
| **Listing Snapshot** | A timestamped record of advertised Vehicle facts and price. It is immutable evidence, not current truth. |
| **Comparable** | A vehicle observation selected under a declared matching policy for a Market Assessment. |
| **Market Assessment** | A versioned market range with comparable set, adjustments, data vintage, and confidence. Never “true value.” |
| **Document Artifact** | An original uploaded file or image retained as evidence. |
| **Document Extraction** | A versioned set of candidate facts, source locations, confidence, and verification state derived from an artifact. |
| **Verified Term** | A Deal Term confirmed by the Buyer or accepted under the configured high-confidence policy. |
| **Deal Terms** | Normalized financial facts: prices, discounts, rebates, fees, add-ons, taxes, government charges, trade terms, and financing. |
| **Dealer Offer** | Deal Terms captured from a Document Artifact and verified by the Buyer. |
| **Deal Plan** | Suggested price boundaries plus Buyer-entered limits for a Buying Case. |
| **Approved Baseline** | An immutable Buyer-approved Deal Plan and Deal Terms version used for later comparisons. |
| **Deal Review** | A reproducible comparison of a Dealer Offer with a specific Approved Baseline. |
| **Discrepancy** | A missing, uncertain, or changed term found by a Deal Review. It is not automatically an error, scam, or fraud. |
| **Financing Scenario** | A reproducible calculation using specified principal, APR, term, fees, and timing. |
| **Out-the-Door Total** | Purchase charge before trade allowance, payoff, cash down, or financing: vehicle price, dealer charges, taxes, and government charges. |
| **Total Transaction Cost** | Cash paid plus scheduled financing payments and known deferred charges needed to complete and repay the purchase. |
| **Deal Analysis Report** | An immutable, versioned presentation of verified evidence and deterministic analysis for a Buying Case. |
| **Buyer Account** | A passwordless identity that holds a Buyer's journeys, contact preferences, and notifications. |
| **Buying Journey** | One Buyer's search for one car: a Shopping Brief, its candidates and shortlist, and the Buying Cases opened from it. |
| **Shopping Brief** | The Buyer's structured criteria for the car they want. Versioned; edits create a new version. |
| **Reference Vehicle** | A VIN the Buyer likes, decoded into attributes that refine matching. It is not necessarily for sale. |
| **Candidate Vehicle** | A vehicle for sale returned by inventory search for a Shopping Brief, with match reasons and a Listing Snapshot. |
| **Shortlist** | The Candidate Vehicles a Buyer has kept for comparison. |
| **Negotiation Mandate** | The Buyer's approved limits for drafts in one Buying Case: target, walk-away ceiling, permitted disclosures, and expiry. It limits what the platform drafts; it authorizes no one to act for the Buyer. |
| **Send path** | How an approved draft reaches the dealer from the Buyer's own address: **Own email app** (copy or `mailto:`, sent by the Buyer) or **Connected mailbox** (sent by the platform through the Buyer's Gmail or Outlook account). |
| **Mailbox Connection** | A Buyer's revocable, send-only authorization for the platform to send approved drafts through one Gmail or Outlook account. |
| **Dealer Conversation** | The Buyer's thread with one Dealer about one Buying Case as kept in the platform: drafts, gate decisions, messages the Buyer sent, and dealer replies the Buyer added. |
| **Quote Request** | A message asking a Dealer for an itemized out-the-door quote on a specific Vehicle. |
| **Agreed Price** | A written dealer quote the Buyer explicitly accepted. Non-binding on either party; it becomes the Approved Baseline. |
| **Test Drive Appointment** | A time and place the Buyer agreed with the Dealer, with the quote reference it relies on. |
| **Desk Check** | A Deal Review of paperwork presented at the dealership against the Agreed Price, or a fresh analysis when none exists. |

The product uses **walk-away ceiling** in the UI for the maximum approved economic boundary. In the domain model it is a limit within the Deal Plan; it is never inferred from a monthly-payment preference.

## 4. Product principles and invariants

1. **Evidence before confidence.** Every material displayed fact has a source, timestamp, and confidence or is labeled missing.
2. **Deterministic money.** AI cannot calculate authoritative totals, set thresholds, approve terms, or change outcomes.
3. **Buyer control.** Only explicit Buyer action creates an Approved Baseline or changes a maximum.
4. **Immutable history.** Corrections and refreshes create versions; they never erase the original evidence or a delivered report.
5. **Separate economics.** Purchase price, financing, trade, and optional products remain visible independently.
6. **No invented precision.** Sparse, stale, conflicting, or unsupported data degrades confidence or yields an unavailable result.
7. **No unsupported accusations.** Unknown or unusual terms are questions, not claims of illegality or fraud.
8. **No false assurance.** Financial consistency does not imply legal validity, mechanical quality, or purchase safety.
9. **Fail closed for positive outcomes.** Missing or uncertain material fields prevent an unqualified positive review.
10. **Consumer alignment.** Commercial relationships cannot change ordering or analysis.
11. **The Buyer acts, the platform assists.** The platform drafts within an active Negotiation Mandate,
    never above its ceiling and never committing the Buyer. Only the Buyer sends, from the Buyer's own
    address, after approving the exact message.
12. **Deterministic gate before every hand-off.** A draft is copied, opened in the Buyer's mail app, or
    sent through a Mailbox Connection only after a deterministic policy gate verifies its amounts,
    disclosures, and commitments against the mandate. The Buyer remains free to write anything outside
    the platform.
13. **Private by default.** A draft includes the Buyer's personal details only where the Buyer chose
    them; the platform never gives Buyer data to a dealer by any other route.

## 5. Primary workflow

Sections 5.1–5.5 are the Phase 1 workflow; a Buying Case may start there directly (for example, at the
desk). Sections 5.6–5.9 add the journey that leads into it.

### 5.1 Start a Buying Case

The Buyer provides:

- buyer ZIP code;
- dealer ZIP or address;
- purchase method: cash or finance;
- listing URL, VIN, or manual Vehicle facts;
- optional maximum selling price and maximum OTD price.

The system creates a high-entropy case link. Email is requested only for recovery, delivery, or research follow-up. Before upload, the system discloses processing, retention, deletion, optional human review, and optional product-research consent separately.

### 5.2 Establish Vehicle and listing evidence

The system normalizes VIN/provider/manual facts, captures a Listing Snapshot when licensed access supports it, and asks the Buyer to resolve material conflicts. A VIN mismatch between the listing, document, and Buyer confirmation is blocking.

Unsupported listing retrieval falls back to VIN/manual input. It must not become a hidden scraping dependency.

### 5.3 Upload and verify a Dealer Offer

Processing pipeline:

```text
validate signature/type/size
  → malware scan
  → isolated page and image normalization
  → OCR/layout extraction
  → schema-constrained document extraction
  → deterministic validation and cross-footing
  → Buyer review and correction
  → verified Dealer Offer
```

The verification screen shows original wording, normalized category, value, confidence, and page/region evidence. Material low-confidence values require confirmation. Corrections preserve the candidate value and append an audit event.

### 5.4 Assess and plan

After material inputs are verified, the system creates:

- a Market Assessment with range, comparables, adjustments, vintage, method version, and confidence;
- recomputed deal totals and reconciliation;
- finance calculations when sufficient inputs exist;
- a Deal Plan containing suggested opening and target values plus a policy-derived maximum;
- Buyer-approved selling-price and/or OTD limits.

The walk-away ceiling is the lower of the policy-derived maximum and the Buyer's applicable approved maximum. No downstream component can raise it.

### 5.5 Generate and use the report

The report leads with a concise outcome, top findings, and next action, followed by evidence and calculations. The Buyer may copy/edit suggested questions, export the report, or provide a revised Dealer Offer.

A revised offer produces a new extraction, Dealer Offer, Deal Review, and report version. It is compared line by line with both the Approved Baseline and immediately preceding offer.

### 5.6 Shop

The Buyer signs in and creates a Buying Journey with a Shopping Brief and optional Reference Vehicles.
VINs are checksum-validated and decoded; decoded attributes are shown to the Buyer, who chooses which
ones matter (for example, trim and drivetrain but not color). Inventory search runs against a licensed
provider within the Buyer's radius and returns Candidate Vehicles with:

- why each matches and which criteria it misses, never a hidden score;
- a Listing Snapshot with source and retrieval time;
- a listed-price market check using the Market Assessment module, labelled as the advertised price,
  not a quote.

Ordering uses only the Buyer's criteria and evidence (credo 3). The Buyer shortlists candidates and
picks one or more; each pick opens a Buying Case seeded with the Vehicle and Listing Snapshot.

### 5.7 Request a quote and negotiate

Before any dealer contact the Buyer approves a Negotiation Mandate for that Buying Case. The platform
proposes a target and walk-away ceiling from the Deal Plan (Section 5.4); the Buyer can lower either,
and the ceiling can never exceed the Buyer's maximum. The mandate also records what may be disclosed
(for example, "pre-approved financing", "paying cash", the Buyer's first name), the autonomy mode, and
an expiry.

The platform drafts a Quote Request. The Buyer approves the exact text and sends it from their own
address by either send path: **Own email app** (copy it, or open it pre-filled with the dealer's
address and subject, and send it from their mail app, then mark it sent) or **Connected mailbox**
(the platform sends it through the Buyer's connected Gmail or Outlook account). The message is the
Buyer's own; it carries no platform footer. Dealer replies reach the Buyer's inbox, and the Buyer adds
them to the Dealer Conversation by pasting the text, forwarding the email to the case's private intake
address, or uploading attachments. Quote attachments and quote figures in message bodies become
Document Artifacts and go through the existing extraction and verification pipeline. Every quote
produces a Deal Analysis Report that tells the Buyer whether it is a good price and why.

Counteroffers are drafted by the Explanation and negotiation modules from verified facts and
deterministic boundaries only, then pass the policy gate (Section 12). The Buyer approves, edits, or
declines each one; an edited draft is gated again before it can be handed off. Negotiation stops when the dealer's itemized quote is at or
below target, when the dealer declines to move, when the mandate expires, or when the Buyer stops it.

### 5.8 Agree and schedule a test drive

The Buyer accepts a quote. If it is not already written and itemized, the platform drafts an email
asking the Dealer to confirm it in writing, and the confirmed quote becomes the Agreed Price and the
Approved Baseline. No draft tells a dealer the Buyer will buy; it says the Buyer accepts the quote
subject to a test drive and the final paperwork matching it.

The platform then drafts a test-drive request from the Buyer's availability. The Buyer chooses which
contact details, such as a phone number, the draft includes, and sends it by either send path. The Buyer receives a calendar file, a
reminder, and a one-page summary of the Agreed Price to bring.

### 5.9 Desk Check

At the dealership the Buyer opens the journey on a phone and uploads the paperwork presented. A Desk
Check compares it line by line with the Agreed Price and leads with any difference: added fees or
add-ons, a changed price, changed financing, or a different VIN. If the Buyer switches to another car,
or never negotiated, the Buyer starts a Buying Case from that car's VIN and paperwork and receives the
Phase 1 analysis, including the jurisdiction's fees and taxes. A Desk Check has the same p95 latency
target as Phase 1 document review and is usable one-handed at 360 px.

If the Buyer is trading a car in, the platform asks once per journey for the trade's VIN, mileage, and
condition, whether money is owed on it, and if so the lender and payoff amount. These carry into every
Buying Case in the journey, including a fresh desk case for a different car. When desk paperwork shows
a trade allowance, the Desk Check compares it with the Buyer's best unexpired written trade offer and a
licensed valuation range, checks the payoff the dealer used against the Buyer's, and shows the trade's
equity. Negative equity added to the amount financed is shown with its cost over the loan.

## 6. Workflow state model

The Phase 1 Buying Case states below are unchanged. A Buying Journey adds `SHOPPING`, `NEGOTIATING`,
`AGREED`, `TEST_DRIVE_SCHEDULED`, `CLOSED`, and `ABANDONED`, and each Buying Case under it adds a
negotiation sub-state: `NO_MANDATE`, `MANDATE_ACTIVE`, `AWAITING_DEALER`, `AWAITING_BUYER`, `AGREED`,
`STOPPED`, `EXPIRED`. Mandate expiry, revocation, or case deletion cancels pending drafts and stops
Connected-mailbox sends immediately.

`AnalysisCase.status` is one of:

```text
DRAFT
DOCUMENT_PROCESSING
NEEDS_REVIEW
READY_TO_ANALYZE
ANALYZING
COMPLETE
PARTIAL
FAILED
DELETION_PENDING
DELETED
```

Allowed happy path:

```text
DRAFT → DOCUMENT_PROCESSING → NEEDS_REVIEW → READY_TO_ANALYZE
      → ANALYZING → COMPLETE | PARTIAL
```

Rules:

- Any non-deleted state may enter `DELETION_PENDING`; only verified lifecycle completion enters `DELETED`.
- Processing failure enters `FAILED` without discarding the artifact or verified case state. Retry resumes the failed stage idempotently.
- `PARTIAL` means a useful report exists but one or more analysis areas are explicitly unavailable. It is not a successful verification verdict.
- A correction to a material term invalidates dependent derived results and moves the case to `READY_TO_ANALYZE` after required verification.
- A delivered `AnalysisReport` is immutable. Regeneration creates the next version.
- Duplicate create/upload/analyze requests with the same idempotency key return the existing resource or operation.

## 7. Review and report outcomes

### 7.1 Deal Review status

- `MATCHES_APPROVED_BASELINE`: all required material fields are verified and no material discrepancy exceeds the configured tolerance.
- `REVIEW_REQUIRED`: at least one material discrepancy, limit violation, or unresolved question exists.
- `UNABLE_TO_VERIFY`: document quality, missing pages, unsupported structure, identity conflict, or missing material data prevents reliable comparison.

`MATCHES_APPROVED_BASELINE` must always be accompanied by the scope and time of checks. The UI label is “Matches approved deal,” never “safe to sign.”

### 7.2 Market/deal rating

Separately from review status, the Deal Analysis Report may show:

- `STRONG_DEAL`
- `FAIR_NEGOTIATE`
- `POOR_DEAL`
- `INSUFFICIENT_DATA`

The rating is calculated from normalized OTD economics only when OTD inputs are sufficiently complete. Otherwise, use a clearly labeled selling-price analysis or `INSUFFICIENT_DATA`. A favorable rating cannot override `REVIEW_REQUIRED` or `UNABLE_TO_VERIFY`.

### 7.3 Discrepancy severity

- `BLOCKING`: wrong/mismatched Vehicle, unreadable material amount, missing page, or calculation input that prevents reliable review.
- `MATERIAL`: exceeds the configured absolute, percentage, or category-specific threshold.
- `MINOR`: below materiality but not hidden, including rounding differences.
- `INFORMATIONAL`: no economic delta but relevant context or question.

Every Discrepancy records expected value, observed value, delta where meaningful, severity, reason code, evidence, confidence, and recommended Buyer action.

## 8. Functional requirements

### 8.1 Buying Case, access, and consent

- **CASE-01:** Create, retrieve, export, expire, and delete a Buying Case without requiring an account.
- **CASE-02:** Protect case links with at least 128 bits of entropy; optional email recovery uses expiring single-use links.
- **CASE-03:** Store jurisdiction, Vehicle, evidence, consents, Dealer Offers, Deal Plans, reviews, reports, and audit events.
- **CASE-04:** Create immutable versions for material Buyer approvals, corrections, offers, and reports.
- **CASE-05:** Record separate, revocable consent for service processing, human review, and anonymized product research. Research consent is never required for service.
- **CASE-06:** A Buyer correction cannot overwrite source evidence.

### 8.2 Vehicle and market data

- **VEH-01:** Accept VIN, manual structured facts, and approved provider facts.
- **VEH-02:** Validate VIN checksum when applicable and surface conflicting identity data.
- **VEH-03:** Preserve material listing facts, source, retrieval time, provider/license metadata, and raw snapshot reference.
- **MKT-01:** Return a range rather than an unexplained point value.
- **MKT-02:** Show included and excluded Comparables and material match/adjustment reasons.
- **MKT-03:** Record geography, vintage, sample size, method version, and confidence.
- **MKT-04:** Filter duplicates, incompatible variants, stale observations, and implausible price/mileage using versioned rules.
- **MKT-05:** Sparse or divergent data widens the range, lowers confidence, or returns `INSUFFICIENT_DATA`.

### 8.3 Document intake and verification

- **DOC-01:** Accept PDF, JPEG, PNG, and HEIC within configured file, page, pixel, and resolution limits.
- **DOC-02:** Reject encrypted, executable, malformed, unsupported, oversized, or unsafe files with an actionable message.
- **DOC-03:** Preserve page order and detect likely missing, duplicate, rotated, or cropped pages.
- **DOC-04:** Store every candidate field's source text, normalized value/unit, page/region, confidence, verification state, extractor/schema version, and correction history.
- **DOC-05:** Extract document identity, Vehicle identity, parties, dates, line items, totals, finance fields, and conditional language.
- **DOC-06:** Run deterministic currency, subtotal, duplicate-line, VIN, date, bounds, and financing consistency validation.
- **DOC-07:** Require Buyer confirmation for all financially material values and every material value below the configured confidence threshold.
- **DOC-08:** Treat document text as untrusted data and never as system or tool instructions.

### 8.4 Deal economics

- **DEAL-01:** Normalize negotiated price, discounts, conditional and unconditional rebates, dealer fees, add-ons, taxable/non-taxable status, taxes, government charges, trade allowance/payoff, down payment, credits, and lender charges.
- **DEAL-02:** Preserve unknown items as `UNKNOWN`; never silently map them to a favorable or government category.
- **DEAL-03:** Recompute subtotals and totals with decimal arithmetic and disclosed rounding.
- **DEAL-04:** Store versioned inputs, formula/policy versions, steps, assumptions, and output for every calculation.
- **DEAL-05:** Compare Dealer Offers against the Approved Baseline and prior offer, including minor differences.
- **DEAL-06:** Separate cash price, OTD Total, net amount due, amount financed, and Total Transaction Cost.
- **DEAL-07:** Identify conditional incentives and exclude unverified eligibility from guaranteed savings.
- **DEAL-08:** Show each add-on's cash price and, when financed, its total financed effect.
- **DEAL-09:** Never count down payment, trade allowance, or Buyer-selected financing as a dealer discount.

### 8.5 Financing

- **FIN-01:** For a standard amortizing loan, compute payment, total payments, total interest, and Total Transaction Cost from verified inputs.
- **FIN-02:** Reconcile a quoted payment and show the tolerance and difference.
- **FIN-03:** Support zero APR. Flag balloons, irregular schedules, and insufficient inputs as unsupported/manual review.
- **FIN-04:** Compare the dealer offer with one manually entered outside offer using aligned purchase assumptions.
- **FIN-05:** Show the incremental full-term cost of APR and financed add-ons.
- **FIN-06:** Do not fabricate a result when principal, APR, term, or required timing is missing.

### 8.6 Deal Plan and assistance

- **PLAN-01:** Calculate opening, target, and policy maximum from a versioned rule set and explicit Market Assessment inputs.
- **PLAN-02:** Calculate and label selling-price and OTD boundaries separately; convert only when jurisdiction inputs are complete enough.
- **PLAN-03:** Cap the walk-away ceiling at the Buyer's approved maximum.
- **PLAN-04:** Warn when the Buyer maximum appears incompatible with the selected Vehicle without manufacturing a lower market value.
- **PLAN-05:** Express estimated savings against a named baseline with uncertainty; never guarantee savings.
- **NEG-01:** Generate editable questions and drafts only from verified case facts and deterministic findings.
- **NEG-02:** Drafts cannot invent competing offers, legal claims, commitments, or dealer statements.
- **NEG-03:** A draft is handed off by either send path only under an active Negotiation Mandate, after the policy gate, and only after the Buyer approves that exact message.

### 8.7 Report, operations, and deletion

- **RPT-01:** Present status, top three reasons, and next action before detailed analysis.
- **RPT-02:** Every material amount links to source evidence or a reproducible calculation.
- **RPT-03:** Label every analysis value as exact, estimated, or unavailable.
- **RPT-04:** State as-of time, jurisdiction coverage, sources, policy versions, limitations, and excluded considerations.
- **RPT-05:** Generate accessible HTML first; PDF is a rendering of the same immutable payload.
- **OPS-01:** Provide redacted failure diagnostics, provider status, rule/version lookup, consented review queue, and deletion verification without broad document browsing.
- **OPS-02:** Meter provider/model usage and cost by Buying Case without exposing sensitive content in logs.
- **DEL-01:** Default raw artifact retention is 30 days or less, configurable downward by the Buyer.
- **DEL-02:** Case deletion revokes access immediately and schedules removal of originals, normalized pages, OCR artifacts, exports, caches, and derived personal data.
- **DEL-03:** Record deletion progress and accurately disclose backup expiry rather than promising immediate erasure from immutable backups.

### 8.8 Buyer Account and journey

- **ACCT-01:** Sign in by expiring single-use email link; no passwords. Phase 1 cases remain usable without an account and can be attached to one.
- **ACCT-02:** A Buyer can export or delete their account and every journey, conversation, and case under it under the Section 8.7 deletion rules.
- **ACCT-03:** Contact details are stored once, encrypted, and placed in a draft only by an explicit per-dealer Buyer choice that is audited.

### 8.9 Shopping

- **SHOP-01:** Capture a versioned Shopping Brief with validated structured criteria.
- **SHOP-02:** Decode Reference Vehicle VINs through a replaceable VIN decoder adapter; show decoded attributes and let the Buyer pick which ones matter.
- **SHOP-03:** Search licensed inventory through a replaceable adapter with recorded provider, license, and retrieval time; no scraping.
- **SHOP-04:** Return each candidate's matched and missed criteria; no unexplained score.
- **SHOP-05:** Order candidates only by Buyer criteria and evidence. A test proves that dealer identity and any commercial field cannot change ordering.
- **SHOP-06:** Show a listed-price market check per candidate, labelled as an advertised price.
- **SHOP-07:** Persist a shortlist and open a Buying Case from a candidate with its Listing Snapshot.

### 8.10 Message Assistant and dealer communication

- **AGT-01:** Create an immutable, versioned Negotiation Mandate only by Buyer approval; a new version requires new approval; revocation is immediate.
- **AGT-02:** The walk-away ceiling in a mandate is at most the Deal Plan's walk-away ceiling (PLAN-03).
- **AGT-03:** Dealer email goes only from the Buyer's own address, by the Own-email-app or Connected-mailbox send path. The platform never sends from an address of its own.
- **AGT-04:** A message is the Buyer's own: it carries no platform footer or platform identity. The platform records which send path was used and when the Buyer sent or marked it sent.
- **AGT-05:** Every draft passes a deterministic policy gate before it is copied, opened in the Buyer's mail app, or sent through a Mailbox Connection: amounts at or below the mandate ceiling, only permitted disclosures, no commitment, deposit, signature, or purchase language, no invented competing offers or facts, and no Buyer personal data the Buyer did not choose.
- **AGT-06:** Every hand-off requires the Buyer's approval of that exact text. There is no delegated or automatic sending.
- **AGT-07:** Inbound dealer content is untrusted (DOC-08). Dealer replies enter only by Buyer paste, forward to the case's private intake address, or upload. Quotes in attachments or bodies become Document Artifacts and are verified before analysis.
- **AGT-08:** The Buyer sees the full conversation, including drafts, gate decisions, send path, and, for Connected-mailbox sends, delivery status.
- **AGT-09:** Compare quotes across a journey's Buying Cases on OTD total, fees, add-ons, and market position.
- **AGT-10:** Create an Agreed Price only from Buyer acceptance of a written, itemized dealer quote; it becomes the Approved Baseline.
- **AGT-11:** Meter and cap Connected-mailbox sends per dealer, per case, and per account.
- **AGT-12:** A Mailbox Connection uses the provider's send-only permission (Gmail `gmail.send`; Microsoft Graph `Mail.Send`), never mailbox read access. Tokens are stored encrypted, never logged, and deleted when the Buyer disconnects or deletes the account. Disconnecting stops sends immediately.
- **AGT-13:** A Connected-mailbox send whose outcome is unclear is never retried automatically. Because a send-only permission cannot look the message up, the Buyer is asked to check their Sent folder and either confirm it was sent or retry it.

### 8.11 Test drive and desk

- **APPT-01:** Draft a test-drive request from Buyer availability for the Buyer to send by either send path; once the Buyer records the dealer's confirmation, send the Buyer a calendar file and reminder.
- **APPT-02:** A booking draft includes only the contact details the Buyer selects.
- **DESK-01:** A Desk Check compares desk paperwork with the Agreed Price and leads with differences.
- **DESK-02:** Support four-square worksheets as a document type, extracting price, trade, down payment, payment, term, and APR fields with evidence.
- **DESK-03:** Starting a fresh case at the desk from a VIN and paperwork needs no prior journey.
- **DESK-04:** Name common desk tactics in findings, each with its dollar effect where calculable and a plain question the Buyer can ask: payment packing, a term stretched to lower the payment, negative equity folded into the loan, a generous trade allowance offset by a higher price, and a dealer APR above the Buyer's own financing offer.
- **TRADE-01:** A Buyer records a Trade Vehicle once per journey, or per case without a journey: VIN, mileage, condition, and whether money is owed; if owed, the lender, payoff amount, and good-through date. It carries into every case in the journey.
- **TRADE-02:** A Buyer may add written trade offers they obtained themselves, with amount, source, date, expiry, and evidence. The platform never requests, arranges, sells, or refers trade offers.
- **TRADE-03:** A trade value range comes only from a licensed valuation provider, labelled with source, retrieval date, and condition assumption. When unavailable it is shown as unavailable, never estimated.
- **TRADE-04:** When paperwork shows a trade allowance, compare it with the Buyer's best unexpired written offer and the valuation range, check the paperwork's payoff against the Buyer's, and show trade equity. A shortfall is shown in dollars; it is never counted as a dealer discount (DEAL-09).
- **TRADE-05:** Judge trade allowance and vehicle price together: an allowance above the trade's value that is offset by a higher price or smaller discount is shown as its net effect.
- **NOTE-01:** Notify the Buyer by email for dealer replies, approvals needed, mandate expiry, and appointments, with per-type opt-out.

## 9. Canonical financial model

```text
vehicle subtotal
  = negotiated vehicle price
  - applicable discounts
  - verified applicable rebates

pre-tax dealer total
  = vehicle subtotal
  + dealer fees
  + add-ons and accessories

out-the-door total
  = pre-tax dealer total
  + taxes
  + title, registration, and other government charges

trade equity
  = trade-in allowance
  - trade-in payoff
  (negative when more is owed than the allowance; the shortfall is
   negative equity and raises the net amount due)

net amount due
  = out-the-door total
  - trade-in allowance
  + trade-in payoff
  - cash down payment
  - other credits

amount financed
  = net amount due
  + financed lender charges

total transaction cost
  = cash paid at or before closing
  + total scheduled loan payments
  + known deferred charges
```

All money is stored as currency plus integer minor units where possible. Rates use decimal values with sufficient precision. Intermediate calculations retain full precision; rounding occurs only at a policy-declared step. Jurisdiction packs declare place, effective interval, authoritative sources, taxable categories, formulas, and rounding. Outside validated coverage, results are estimates or unavailable.

Standard fixed-rate financing uses the ordinary annuity formula, with an explicit zero-rate branch. Contract fixtures define payment timing and rounding behavior.

## 10. Data model

`AnalysisCase` (the persistence name for Buying Case) is the aggregate root.

| Record | Required responsibilities |
|---|---|
| `AnalysisCase` | Access token hash, jurisdiction, purchase method, workflow state, Buyer constraints, consent references, selected Vehicle, current report, timestamps. |
| `Vehicle` | VIN and normalized year/make/model/trim/body/drivetrain/mileage facts with source and verification. |
| `Dealer` | Public business identity and location only. |
| `ListingSnapshot` | Immutable advertised facts, URL, Dealer, retrieval time, provider/version/license metadata, raw reference. |
| `SourceDocument` | Object key, checksum, verified media type, size/pages, lifecycle, retention deadline, encryption metadata. |
| `DocumentExtraction` | Artifact, extractor/model/schema versions, status, candidate fields, confidence, provenance. |
| `QuoteLineItem` | Original label, normalized category, amount, tax/option claims, confidence, evidence, verification/correction. |
| `DealerOffer` | Immutable verified Deal Terms assembled from one or more extraction versions. |
| `MarketAssessment` | Provider values, frozen comparable set, exclusions, adjustments, range, confidence, policy version. |
| `FinancingScenario` | Principal, APR, term, timing, payment/fees, source, assumptions, calculated outputs. |
| `DealPlan` | Suggested opening/target/policy maximum and Buyer limits; approval creates an Approved Baseline version. |
| `DealReview` | Baseline version, offer version, previous offer, status, discrepancies, tolerance policy. |
| `AnalysisReport` | Immutable schema-versioned snapshot referencing all evidence and policy versions. |
| `ConsentRecord` | Purpose, policy version, grant/revoke actor and timestamps. |
| `AuditEvent` | Append-only material action metadata; no raw sensitive document text. |
| `DeletionJob` | Scope, lifecycle, attempts, completion evidence, backup-expiry metadata. |
| `ProviderUsage` | Provider, operation, request correlation, units, cost, status, license metadata. |
| `BuyerAccount` | Email identity, encrypted contact details, notification preferences, timestamps. |
| `BuyingJourney` | Account, Shopping Brief versions, state, Buying Cases opened from it. |
| `ShoppingBrief` | Versioned structured criteria. |
| `ReferenceVehicle` | VIN, decoded attributes, attributes the Buyer marked as relevant, decoder version. |
| `CandidateVehicle` | Search run, Listing Snapshot reference, matched/missed criteria, market check, shortlist flag. |
| `NegotiationMandate` | Case, version, target, ceiling, permitted disclosures, expiry, approval/revocation actor and time. |
| `DealerConversation` | Case, Dealer, dealer email address, private reply-intake address. |
| `DealerMessage` | Direction, body reference, attachments, draft/approval/gate decision, send path, sent-or-marked-sent time, delivery status for Connected-mailbox sends. Bodies follow artifact retention. |
| `MailboxConnection` | Account, provider, sending address, granted scope, encrypted tokens, connection and revocation time. |
| `AgreedPrice` | Case, accepted Dealer Offer version, acceptance actor and time, written-confirmation evidence. |
| `TestDriveAppointment` | Case, time, location, confirmation evidence, contact-release record. |
| `Notification` | Account, type, channel, delivery state; no sensitive content in the payload. |

Material records include IDs, case ID, creation time, source, schema/policy version, and actor. Original document bytes never live in PostgreSQL.

## 11. Architecture

Build a modular monolith with one background worker. The initial reference stack is React + TypeScript, Java + Spring Boot, PostgreSQL, encrypted object storage, and a managed queue. The architecture is a product boundary, not authorization to choose a cloud or vendor before diligence.

### 11.1 Deep modules

- **Buying Case:** owns lifecycle, evidence timeline, approvals, current baseline, and orchestration.
- **Document Understanding:** turns an artifact into candidate normalized fields with provenance. OCR/model vendors remain internal adapters.
- **Vehicle Identity:** reconciles VIN/manual/provider facts.
- **Market Assessment:** selects Comparables and returns a range, adjustments, confidence, and version.
- **Deal Engine:** performs fee classification, OTD arithmetic, financing, reconciliation, and Deal Review. No LLM calls.
- **Deal Policy:** produces ratings and price boundaries under Buyer caps.
- **Report:** assembles immutable structured output and evidence references.
- **Explanation:** converts finalized structured results to plain language and cannot introduce facts.
- **Shopping:** owns Shopping Briefs, Reference Vehicles, search runs, candidates, and shortlists.
- **Message Assistant:** owns mandates, Dealer Conversations, drafting, the policy gate, the send paths, Mailbox Connections, and Agreed Prices. It reads Deal Policy boundaries and cannot change them.
- **Scheduling:** owns availability, appointments, calendar files, and reminders.
- **Accounts and Notifications:** owns sign-in, contact-detail release, and Buyer notifications.
- **Consent and Retention:** owns purpose grants, access expiry, deletion, and audit.
- **Provider Metering:** normalizes operational usage and licensing metadata.

Provider-specific response objects must be normalized at ingestion and must not leak across module interfaces.

### 11.2 External adapters

- VIN decoder.
- Licensed inventory/valuation provider.
- Licensed trade-in valuation provider.
- Jurisdiction/tax/government-fee source.
- OCR/layout and vision-capable structured extraction provider.
- Object storage, malware scanner, email delivery, and optional consented human-review queue.
- VIN decoder for Reference Vehicles and candidates.
- Licensed dealer inventory search.
- Send-only mailbox adapters for Connected-mailbox sends (Gmail API, Microsoft Graph).
- Inbound intake for dealer replies the Buyer forwards to a case's private intake address.

Every adapter has a deterministic fake or licensed recorded fixture for the main test suite. Main tests do not call paid live endpoints.

### 11.3 Public workflow API

The versioned API must support:

```text
create case
attach/confirm vehicle evidence
request upload and attach Document Artifact
read/correct Document Extraction
create verified Dealer Offer
add Buyer limits and optional outside Financing Scenario
approve Deal Plan / create Approved Baseline
request analysis
read operation status
retrieve/export Deal Analysis Report
submit optional outcome feedback
delete case
```

Upload, correction, baseline approval, analysis, export, and deletion operations require idempotency keys. Long-running endpoints return operation IDs and explicit stage state.

## 12. AI execution policy

- Uploaded and provider content is untrusted input, including apparent instructions embedded in text.
- Models receive only the minimum data needed for extraction or explanation.
- Extraction requires schema-constrained output and deterministic validation.
- Unreadable values become missing or low-confidence; the model must not guess digits.
- Model output cannot mutate a Verified Term, approval, limit, policy, state, or sent communication.
- Models draft dealer messages; they cannot send them. A draft reaches a dealer only through a send path, after the policy gate (AGT-05) and the Buyer's approval of the exact text.
- Dealer replies are untrusted input. Instructions in them (for example, "reply with the buyer's phone number") are content, not commands.
- Negotiation amounts in drafts come from deterministic boundaries and the mandate; the gate rejects any amount a model introduced.
- Explanations consume only finalized structured facts and findings. Displayed claims are checked against those inputs.
- Record model, prompt, schema, thresholds, and evaluation-set versions for material outputs.
- Model/provider changes must pass a frozen corpus and shadow comparison before rollout.
- Customer documents are not used for training. Vendor retention and training controls require contractual validation.

## 13. Security, privacy, compliance, and accessibility

- TLS in transit; encryption at rest; short-lived signed object access; least-privilege service identities; managed secrets; separated development, staging, and production data.
- Verify file signatures, isolate parsing, scan malware, limit size/pages/decompression, and rate-limit abuse.
- Never put raw documents, raw text, access links, Buyer identity plus VIN, or financing details into ordinary logs, analytics, support tooling, or error trackers.
- Collect no SSN, driver's license, bank credential, or payment credential for analysis.
- Maintain an inventory of providers, purposes, subprocessors, regions, retention, display/caching rights, and deletion behavior.
- Complete threat modeling, dependency/container scanning, SAST, secret scanning, upload fuzzing, tenant/access isolation tests, backup restoration, and an incident runbook before public pilot.
- Before charging Buyers, obtain a jurisdiction-specific legal review of privacy, fee/tax/optionality claims, consumer-protection and savings claims, financial/legal-advice boundaries, retention, and provider terms. A free pilot of the tool model needs published Terms of Service and a Privacy Policy, not counsel sign-off.
- Do not encode the vacated FTC CARS Rule as active law. Legal requirements are versioned jurisdiction policy maintained from authoritative sources and counsel guidance.
- Core flows meet WCAG 2.2 AA, including keyboard use, screen-reader labeling, contrast, focus, and non-color warning cues.
- The platform never contacts a dealer in its own name, so it does not act as the Buyer's broker or representative and sends no commercial email of its own. Dealer messages are the Buyer's personal email. SMS and calls are excluded.
- Mailbox Connection tokens are encrypted at rest, scoped send-only, never logged, and revocable by the Buyer at any time. The forwarded-reply intake address is unguessable per case, accepts mail only while the case accepts it, and treats everything received as untrusted.

## 14. Non-functional requirements

- **Calculation correctness:** 100% pass on audited golden fixtures for supported policies.
- **Material-item extraction:** at least 99% recall for detecting the presence of financially material line items on the frozen supported-document validation set; uncertain values may be routed to review.
- **Traceability:** 100% of displayed material amounts trace to evidence, Buyer input, or a reproducible calculation.
- **Review safety:** zero false `MATCHES_APPROVED_BASELINE` outcomes in the release corpus.
- **Performance:** deterministic recalculation p95 under 500 ms; supported document to review-ready p50 under 60 seconds and p95 under 180 seconds, excluding consented human review.
- **Availability:** 99.5% monthly during public beta, excluding announced maintenance.
- **Deletion:** access revoked immediately on request; active-system deletion completion p95 within 24 hours; backup expiry disclosed and monitored.
- **Accessibility:** no critical WCAG 2.2 AA violations in core workflows.
- **Reproducibility:** historical structured input plus recorded policy/provider snapshots reproduces the same report payload except explicitly versioned presentation metadata.
- **Agent safety:** zero outbound messages above the mandate ceiling, with a commitment, or with unreleased Buyer personal data in the release test corpus; the gate is tested against adversarial drafts.
- **Search latency:** inventory search results p95 under 5 seconds, excluding provider outages, which are stated.
- **Desk Check latency:** the Phase 1 document-review targets apply.

## 15. Acceptance scenarios

1. **Hidden add-ons:** A baseline OTD of $36,150 followed by a quote containing new $399 VIN etching, $899 protection, and $1,495 warranty yields `REVIEW_REQUIRED`, lists all three with evidence, and shows a $2,793 pre-tax delta plus separately calculated tax effect.
2. **Financing mismatch:** Verified principal, APR, and term whose quoted payment exceeds tolerance show quoted/calculated payments, delta, tolerance, and neutral possible causes.
3. **Poor scan:** An unreadable amount-financed field enters `NEEDS_REVIEW` and cannot yield `MATCHES_APPROVED_BASELINE`.
4. **Changed maximum:** An offer above an Approved Baseline limit produces a material limit violation; no model output or provider refresh raises the limit.
5. **Prompt injection:** Text instructing the system to ignore rules or transmit data remains document content and triggers no tool, disclosure, or policy change.
6. **Unknown fee:** An unfamiliar label is preserved and categorized `UNKNOWN`, with a clarification action and no claim that it is a government charge or illegal.
7. **Sparse market:** Insufficient recent Comparables produce low confidence or `INSUFFICIENT_DATA`, not a precise value or savings claim.
8. **VIN mismatch:** Different listing and document VINs create a blocking finding and prevent analysis until the Buyer resolves identity.
9. **Conditional rebate:** A rebate whose eligibility is unverified remains conditional and is excluded from guaranteed OTD savings.
10. **Cash purchase:** A Buyer selecting cash receives a complete applicable report without financing questions blocking progress.
11. **Revised quote:** A second verified Dealer Offer is compared with both the Approved Baseline and first offer, while the first report remains unchanged.
12. **Duplicate request:** Repeating an analyze request with the same idempotency key produces one operation and one report version.
13. **Partial provider failure:** Market-provider failure preserves the case and yields a partial deal-economics report with market analysis unavailable.
14. **Deletion:** Deleting a case immediately invalidates its access link, progresses through every derived artifact, and produces auditable completion without sensitive content in logs.
15. **Financed add-on:** An add-on financed for the full term shows both its cash price and incremental scheduled-payment/interest effect.
16. **Reference VINs:** Two Reference Vehicle VINs for EX-trim Civics refine a Civic brief so EX-trim candidates show "matches your reference trim"; an invalid VIN is refused with a checksum message.
17. **Ranking integrity:** Changing a candidate's dealer, or adding any commercial field, does not change ordering or wording.
18. **Ceiling enforcement:** A model draft offering $500 above the mandate ceiling is blocked by the gate and never sent; the Buyer sees why.
19. **Data leak attempt:** A dealer reply asking for the Buyer's phone number produces no disclosure; the draft reply is gated and the Buyer is informed.
20. **No commitment:** A draft saying "the buyer will take it, deposit to follow" is blocked.
21. **Approved send:** No draft is copied, opened in a mail app, or sent through a Mailbox Connection before Buyer approval; an edited draft is re-gated.
22. **Agreed Price:** Accepting a written itemized quote creates the Agreed Price and Approved Baseline; accepting a verbal or partial quote prompts for written confirmation instead.
23. **Desk add-ons:** Desk paperwork adding a $1,495 service contract to the Agreed Price leads the Desk Check with that addition and its total effect.
24. **Switch cars at the desk:** A Buyer with an Agreed Price on one car uploads a different VIN's four-square and receives a fresh analysis, clearly separate from the agreed car.
25. **Disconnect:** A Buyer who disconnects Gmail or Outlook has the tokens deleted, and a send attempted afterwards is refused with an offer to use their own email app instead.
26. **Mandate revocation:** Revoking a mandate cancels pending drafts and blocks Connected-mailbox sends within one second.
27. **Own email app:** An approved quote request opens in the Buyer's mail app addressed to the dealer with the approved subject and body, or is copied exactly; the conversation records it as sent only when the Buyer marks it sent.
28. **Trade below offer:** A Buyer with a written $14,000 trade offer receives desk paperwork with an $11,500 trade allowance; the Desk Check leads with the $2,500 shortfall and names the offer it came from.
29. **Negative equity:** A Buyer who owes $18,000 on a trade allowed at $15,000 sees $3,000 of negative equity added to the amount financed, with its scheduled-payment and interest cost over the term.
30. **Payoff mismatch:** Desk paperwork using a $19,200 payoff when the Buyer recorded $18,000 good through that day is flagged with the $1,200 difference.
31. **Payment packing:** A four-square payment $40 a month above the payment calculated from its own price, APR, and term is named as a possible packed product, with the question to ask.

## 16. Test strategy

The primary acceptance seam is:

> Given Vehicle evidence, Buyer jurisdiction, Buyer constraints, and a Dealer document, produce a versioned structured Deal Analysis Report.

- Build a legally acquired, de-identified golden corpus covering clean PDFs, scans, phone photos, rotations/crops, multi-page documents, annotations, conditional incentives, duplicate add-ons, negative credits, missing pages, VIN mismatch, financing layouts, and prompt injection.
- Keep train/development/test sets separate even without fine-tuning.
- Score extraction by field, weighting VIN, selling price, totals, APR, term, payment, and amount financed most heavily.
- Use property tests for cross-footing, amortization, monotonic interest behavior, caps, rounding, and the rule that credits/down payments are not discounts.
- Use table-driven jurisdiction and fee-classification tests, including unknown terminology and effective-date changes.
- Use provider contract fixtures and scheduled sandbox smoke tests for timeouts, rate limits, stale/partial data, duplicates, missing VINs, and schema drift.
- Test idempotency, retries, report versioning, unauthorized link access, retention, and deletion at the API workflow level.
- Security-test MIME confusion, decompression bombs, malicious PDFs, object-key enumeration, signed-URL expiry, redaction, rate limiting, and data isolation.
- Run automated accessibility checks plus keyboard and screen-reader walkthroughs.
- Replay historical structured cases for every deterministic engine change; require intentional report-version deltas.

Tests assert observable outputs and contracts, not prompts, private methods, class layout, or a vendor's incidental schema.

## 17. Delivery plan and gates

### Milestone 0 — Foundation and external prerequisites (2–3 weeks)

Deliver the canonical schema, jurisdiction-pack contract, golden financial fixtures, fee taxonomy, report schema, clickable flow, provider spikes, and first labeled corpus.

Gate: selected pilot jurisdiction; provider rights/cost/coverage understood; domain review passes sample deals; deterministic fixtures pass; five target Buyers can explain prototype outcomes.

### Milestone 1 — Case, intake, and extraction (2–3 weeks)

Deliver case access/lifecycle, secure upload, isolated normalization, extraction/provenance, correction, consent, retention/deletion, and corpus evaluation harness.

Gate: material extraction thresholds met on supported layouts; unsafe/unsupported inputs fail safely; privacy/security review passes for private alpha.

### Milestone 2 — Deterministic analysis (3–4 weeks)

Deliver vehicle/provider adapters, Listing Snapshots, Market Assessment, jurisdiction fee/OTD engine, finance engine, Deal Plan, Approved Baseline, revised-offer review, and report-level contract tests.

Gate: calculation fixtures pass 100%; no false positive review in release corpus; policy results reproduce from frozen inputs.

### Milestone 3 — Report and operations (2–3 weeks)

Deliver accessible mobile-first report, evidence links, explanations, Buyer-sent drafts, PDF export, metering, redacted console, feedback, outcome capture, and observability.

Gate: traceability is 100%; end-to-end latency targets met; support can diagnose failures without unrestricted artifact access.

### Milestone 4 — Hardening and pilot (2–4 weeks)

Deliver threat/security/accessibility/resilience tests, legal copy review, operator and incident runbooks, controlled rollout, and metric dashboards.

Gate: 25–50 moderated cases complete safely before a gated 100–300-case pilot; no critical privacy issues; material numeric error below 1%; quality thresholds remain met.

Expected effort: 12–16 calendar weeks for two experienced full-stack engineers plus part-time product/design, automotive expertise, security, and counsel. Provider contracting and corpus acquisition are critical-path dependencies.

Milestones 0–4 make up **Phase 1 (document analysis)**. Its open release items are tracked in
`docs/pilot-readiness-record.md` and ticket 22. Later phases may be built in parallel with the Phase 1
pilot, but no Buyer outside moderated testing uses a later phase before its gate passes.

### Phase 2 — Shop

Deliver Buyer Accounts, Shopping Briefs, Reference Vehicle decoding, the inventory search adapter with
a deterministic fake, candidate matching with explained reasons, listed-price market checks,
shortlists, and opening a Buying Case from a candidate.

Gate: a licensed inventory source under executed terms; ranking-integrity tests pass; moderated Buyers
find and shortlist a suitable car from a brief.

### Phase 3 — Message Assistant

Deliver Negotiation Mandates, drafted Quote Requests and counteroffers, the policy gate, both send
paths, Mailbox Connections, dealer reply intake by paste, forward, or upload, the conversation view,
cross-candidate quote comparison, Agreed Price, and notifications.

Gate: zero gate escapes on the adversarial corpus; Google and Microsoft app verification for the
send-only permissions before Connected mailbox is offered outside testing; moderated journeys obtain
written itemized quotes.

### Phase 4 — Test drive and desk

Deliver test-drive scheduling, contact release, reminders, the Desk Check, four-square extraction, and
the switch-cars path, the trade-in check, and named desk tactics.

Gate: Desk Check meets latency targets on phones; four-square extraction meets Section 14 thresholds on
a permissioned corpus.

## 18. Decisions and prerequisites

The following defaults resolve prior scope ambiguity:

- used dealer retail purchases only;
- Phase 1 sends nothing; from Phase 3 the platform drafts dealer messages and the Buyer sends each one from their own address after approving it, from their own mail app or through a connected Gmail or Outlook account;
- one-time case pricing experiment after private alpha, not subscription; Buyers are the only payers;
- no required account for Phase 1; a passwordless account for Phases 2–4;
- raw-document retention default no longer than 30 days;
- Buyer correction by default; human review only through separate consent;
- one initial offer plus revised-offer comparison;
- modular monolith and worker, not microservices.

Development may begin on provider-independent modules and synthetic jurisdiction fixtures immediately. The following must be resolved before claims or integrations enter a public pilot:

1. Select the pilot state (Florida, chosen 2026-10-05) and source its jurisdiction rules from statute and agency publications, with citations.
2. Obtain written automotive provider terms covering consumer display, caching, attribution, derived values, retention, deletion, and pricing.
3. Acquire at least 100 permissioned representative dealer documents and freeze a held-out evaluation subset.
4. Have a consumer-side automotive expert validate the opening/target/walk-away policy against retrospective deals.
5. Set exact supported upload limits, materiality tolerances, extraction confidence thresholds, market freshness/sample rules, and report schema v1 fixtures.
6. Decide the private-alpha recovery mechanism and cloud/provider stack.
7. Pre-register pilot success and safety thresholds before reading pilot outcomes.

Before Phase 2 and 3 reach Buyers outside moderated testing:

8. Execute terms with a licensed inventory provider covering search, display, caching, and pricing.
9. Publish Terms of Service and a Privacy Policy that describe the tool model: the platform informs and
   drafts, the Buyer decides and sends, and a Mailbox Connection is send-only.
10. Complete Google OAuth app verification for `gmail.send` and Microsoft publisher verification for
    `Mail.Send`, and choose an inbound provider for the forwarded-reply intake address.
11. Execute terms with a licensed trade-in valuation provider covering consumer display, caching, and
    pricing before trade value ranges reach Buyers outside moderated testing.

Open decisions for the product owner:

- **Dealer reputation.** Whether to show Buyers cross-journey evidence about dealers (for example,
  "added fees at the desk in 3 of 5 journeys"). Currently excluded for fairness, privacy, and
  defamation risk; a Buyer still sees their own dealer's conduct.
- **Pricing for the Message Assistant.** Whether Buyers pay a flat fee per journey, per car, or a
  Buyer-paid success fee. Dealer-paid fees are excluded by the credo in every option. Before charging,
  confirm that the paid service stays a tool and not a motor vehicle broker under the pilot state's law.
- **Automatic reply import.** Whether to read dealer replies from a connected mailbox. It needs mailbox
  read access, which Google treats as a restricted scope requiring an annual third-party security
  assessment.
- **New vehicles.** Whether to extend shopping and negotiation to new cars.

## 19. Success measures

Primary pilot measure: percentage of qualified Buyers who complete a Deal Analysis Report and make an understood decision without an unresolved material Discrepancy.

Track:

- upload → verified extraction → completed report conversion;
- time to review-ready extraction and report;
- field precision/recall, numeric exact match, correction rate, and human-review rate;
- seeded discrepancy false-positive/false-negative rates;
- comparable relevance and market-confidence coverage;
- usefulness, clarity, trust, action taken, revised-offer rate, and verified outcome delta;
- willingness to pay at one-time price variants;
- Phase 2: briefs created, candidates shortlisted, and picks per journey;
- Phase 3: dealer response rate, time to first itemized quote, quotes obtained per pick, drafts approved unchanged, gate blocks by reason, and sends by send path;
- Phase 3–4: Agreed Price vs. desk paperwork difference, and the share of Desk Checks with added fees or add-ons;
- per-completed and per-failed-case provider cost;
- refunds, complaints, support contacts, deletion SLA, and privacy/licensing incidents.

Directional gate after 100–300 pilot cases: at least 70% qualified-case completion, 80% usefulness, 40% “very disappointed” among live-use respondents, material numeric error below 1%, no critical privacy event, and credible paid-conversion evidence. Final thresholds must be set before pilot results are reviewed.

## 20. Definition of done

Phase 1 is done when a Buyer can establish a supported Vehicle and jurisdiction, upload a supported Dealer Offer, verify every material extracted term, approve financial boundaries, receive a reproducible and traceable Deal Analysis Report, compare a revised offer, evaluate standard financing when present, export the report, and delete the Buying Case—while no AI component can alter calculations, approvals, limits, review state, or external systems.

The full product is done when a Buyer can, in addition, describe a car and add reference VINs, review
and shortlist explained matches, approve a mandate, send drafted quote requests and counteroffers
within it from their own email address while keeping the whole thread in one view, accept an Agreed
Price, book a test drive, and check desk paperwork against the Agreed Price, with no dealer money, no
Buyer data released without consent, no message sent without the Buyer's approval of its exact text,
and no AI component able to raise a limit, send outside the gate, or commit the Buyer.

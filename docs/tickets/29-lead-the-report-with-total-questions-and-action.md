# 29 — Lead the report with the total, the questions, and the next action

**What to build:** The report answers the three questions a Buyer has while standing at a desk — what this actually costs, what to ask, and what to do next — before any supporting detail.

**Blocked by:** 26 — Carry the Buyer from a verified offer to the report and back.

**Status:** complete — the report opens on the out-the-door total, three stored findings and the
next action at 360 px, with the same order in the PDF export and a pilot measure of time to a correct
total, as of 2026-09-30.

- [x] The out-the-door total, the three most material findings, and one next action appear first, without scrolling, at 360 px width.
- [x] Each leading amount links to its source and calculation rather than restating it.
- [x] The question list is source-grounded, Buyer-editable, and copyable as text.
- [x] Amounts carry their precision, and unknown values are labeled as unknown rather than rendered as zero or omitted.
- [x] The PDF export presents the same ordering and the same values as the HTML report, from the same immutable payload.
- [x] Time from report open to a correctly identified out-the-door total is instrumented as a pilot measure.
- [x] Automated accessibility checks cover the leading section, including heading order, contrast, and keyboard reachability.

## Implementation notes — 2026-09-30

### One traversal for HTML and PDF

`ReportLead` reads the leading total, the first three stored material findings and the saved next
action from the immutable report payload, and both the HTML view and `StoredReportRenderer` use it, so
the two exports cannot disagree and nothing is recomputed. An absent or unavailable amount reads
Unknown in both; precision stays visible.

### Questions stay local

Buyer edits to the question list change only the copy the Buyer sees and copies; they do not alter the
report and do not survive reopening. The exports keep the stored findings.

### Measuring time to a correct total

`POST /api/v1/cases/{caseId}/feedback/report-total` opens a timing session for a report version, and
`POST …/report-total/{id}` checks the Buyer's answer server-side and returns only whether it was
correct; the answer itself is not stored. `V23__report_total_identification.sql` adds `report_version`
to `pilot_events`, and the pilot snapshot reports opens, correct identifications, and mean and p95
time. Samples are client-timed, optional, and capped at 24 hours; they measure comprehension, not
purchase outcomes.

### Limit

The no-scroll guarantee is tested at 360 × 780 with three full-length findings. Much longer finding
text, or enlarged user text, can still need vertical scrolling.

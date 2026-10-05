# 82 — Send dealer email from the Buyer's own email app

**What to build:** Every message the platform drafts for a dealer (the quote request, counteroffers, the
written-confirmation request on acceptance, and test-drive booking, rescheduling and cancellation) can be
sent by the Buyer from their own email app. After the Buyer approves the exact gated text, the
conversation view offers **Open in my email app** (a `mailto:` link with the dealer's address, subject and
body filled in) and **Copy** (subject and body copied separately). The Buyer sends it from their own
mailbox and taps **I sent it**, which records the message as sent and moves the quote, counteroffer,
acceptance or appointment record to the same state a successful relay send reaches today. The draft is
the Buyer's own message: it carries no platform footer and no platform identity. The Buyer adds dealer
replies by pasting the reply text or uploading attachments into the conversation, and they go through
the existing reply intake and document pipeline. This is the first of the two send paths in spec 2.1;
ticket 83 adds the connected mailbox, and ticket 84 retires the platform relay. Spec 2.1 §2.1, §5.7,
§5.8, AGT-03, AGT-04, AGT-05, AGT-06, AGT-07, NEG-03, APPT-01, acceptance scenarios 21 and 27.

**Blocked by:** none — builds on 60, 61, 63 and 65, which are complete.

**Status:** ready-for-agent — filed on 2026-10-05 from specification 2.1.

- [ ] The Buyer enters or confirms the dealer's email address once per Dealer Conversation, validated as an email address and stored with the conversation. No draft can be handed off without one.
- [ ] Handing off a draft requires an active mandate, a passing gate decision on the exact stored text, and the Buyer's approval of that text, rechecked under the case lock. Editing the text gates it again, and a stale approval cannot be used.
- [ ] **Open in my email app** opens a `mailto:` link with the dealer address, subject and body correctly percent-encoded. When the encoded link would exceed a safe length (about 2,000 characters), the button is replaced by Copy with a plain-words explanation.
- [ ] **Copy** copies the exact approved subject and body, with confirmation that is announced to screen readers.
- [ ] Hand-off drafts carry no platform footer, opt-out line, relay address or platform name. The gate still blocks above-ceiling amounts, commitment, deposit, signature or purchase language, invented facts, and Buyer personal data the Buyer did not choose.
- [ ] **I sent it** records the message as sent with the `OWN_EMAIL_APP` send path and the time, once. The quote request moves the case to `AWAITING_DEALER`; counteroffer, acceptance and test-drive records reach the same state as a first-time relay send. Pressing it twice, or after the mandate is revoked or expires, changes nothing and says why.
- [ ] A handed-off message the Buyer has not marked sent stays visible as "Not sent yet" and can be marked sent later or discarded. Nothing is sent or retried automatically.
- [ ] The Buyer adds a dealer reply by pasting its text or uploading attachments into the Dealer Conversation. It is recorded as an inbound message, treated as untrusted, and quote figures or attachments become Document Artifacts through the existing intake, verification and reply-assistance paths.
- [ ] Test-drive drafts include only the contact details the Buyer selects (APPT-02). The calendar file and reminder follow once the Buyer records the dealer's confirmation.
- [ ] Message bodies, addresses and subjects are never logged. New columns or rows are covered by case and account export and deletion.
- [ ] The conversation view works at 360 px in both themes, is in the axe sweep, and Playwright covers approve → open/copy → mark sent → paste reply for a quote request.

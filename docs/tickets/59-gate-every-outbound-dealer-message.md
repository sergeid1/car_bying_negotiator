# 59 — Gate every outbound dealer message

**What to build:** A deterministic policy gate checks every outbound dealer draft against its Negotiation Mandate before it can be sent: amounts at or below the ceiling, only permitted disclosures, no commitment, deposit, signature, or purchase language, no invented competing offers or facts, and no Buyer personal data. An adversarial corpus proves zero escapes. Spec AGT-05, §12, acceptance scenarios 18–20.

**Blocked by:** 57 — Approve a Negotiation Mandate.

**Status:** complete — a deterministic outbound gate in `com.dealanalyzer.outbound` (migration V33), as of
2026-10-03. `OutboundGate.check` (`outbound-gate.v1`, no model call, no I/O) accepts a closed set of message templates
plus the questions `GroundedLanguage` derives from a finalized report, and fails closed on anything else with stable
reason codes and plain-words text. A price question must use the mandate currency and equal its target, its ceiling,
or a verified case amount at or below the ceiling. Commitment language, invented offers or facts, and unreleased Buyer
personal data fail. A frozen corpus of 124 adversarial drafts has zero escapes in the main suite. Drafts and their
decisions (with gate version and a context snapshot) are stored per case and dealer; `OutboundGateDecision.tsx` shows
the reasons and is mounted by ticket 61. Open: the template set is deliberately narrow, so first-name and timeline
disclosures cannot pass until ticket 65 supplies verified releases; a stored pass is not authorization to send —
tickets 60/61 must recheck under the case lock before calling the relay; rejected draft text is stored unencrypted
until case deletion.

- [x] The gate is deterministic, versioned, and has no model call; it returns pass or a list of reasons per violation.
- [x] Every money amount in a draft must equal a value derived from the mandate or verified case facts; an amount above the ceiling, or not traceable to one, fails.
- [x] Commitment language (agree to buy, deposit, sign, hold the car for payment), invented competing offers, and claims not grounded in case facts fail, reusing `GroundedLanguage` where it applies.
- [x] Any Buyer personal data not released for that dealer fails.
- [x] An adversarial corpus of at least 100 drafts (including obfuscated numbers, spelled-out amounts, and indirect commitments) has zero escapes, run in the main suite.
- [x] Gate decisions are stored with the draft and shown to the Buyer in plain words.

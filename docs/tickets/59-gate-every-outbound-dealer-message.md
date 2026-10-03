# 59 — Gate every outbound dealer message

**What to build:** A deterministic policy gate checks every outbound dealer draft against its Negotiation Mandate before it can be sent: amounts at or below the ceiling, only permitted disclosures, no commitment, deposit, signature, or purchase language, no invented competing offers or facts, and no Buyer personal data. An adversarial corpus proves zero escapes. Spec AGT-05, §12, acceptance scenarios 18–20.

**Blocked by:** 57 — Approve a Negotiation Mandate.

**Status:** blocked on 57 — filed on 2026-10-03 from specification 2.0.

- [ ] The gate is deterministic, versioned, and has no model call; it returns pass or a list of reasons per violation.
- [ ] Every money amount in a draft must equal a value derived from the mandate or verified case facts; an amount above the ceiling, or not traceable to one, fails.
- [ ] Commitment language (agree to buy, deposit, sign, hold the car for payment), invented competing offers, and claims not grounded in case facts fail, reusing `GroundedLanguage` where it applies.
- [ ] Any Buyer personal data not released for that dealer fails.
- [ ] An adversarial corpus of at least 100 drafts (including obfuscated numbers, spelled-out amounts, and indirect commitments) has zero escapes, run in the main suite.
- [ ] Gate decisions are stored with the draft and shown to the Buyer in plain words.

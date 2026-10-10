# 100 — Record the trade-in and what is owed on it

**What to build:** A Buyer who is trading a car in records it once: VIN (decoded), mileage and a simple
condition choice, then answers "Do you still owe money on it?" If yes, the platform asks for the lender,
the payoff amount and the date it is good through, and suggests getting a payoff quote from the lender.
The Buyer can also add written trade offers they got themselves (amount, who made it, date, expiry and a
photo or PDF of the offer). The Trade Vehicle belongs to the journey and carries into every Buying Case in
it, including a fresh desk case for a different car; a case without a journey holds its own. Spec 5.9,
TRADE-01, TRADE-02.

**Blocked by:** 51 — Describe the car in a Shopping Brief; 52 — Add reference vehicles by VIN (VIN decoding);
24 — Upload dealer paperwork from a phone (offer evidence upload). Migration V50 is reserved for this ticket.

**Status:** complete — migration V50, as of 2026-10-09. `com.dealanalyzer.trade` holds one Trade Vehicle per
Buying Journey, or per case without a journey, behind case-credential endpoints under
`/api/v1/cases/{caseId}/trade`. The owed answer is YES, NO or NOT_SURE ("I'm not sure"); an unknown payoff is
stored as null, never zero, and a payoff past its good-through date (America/New_York calendar) is marked stale.
Written offers carry their evidence through ordinary document intake as `source_documents.purpose =
'TRADE_OFFER_EVIDENCE'`, which is never extracted, listed or compared as dealer paperwork (Desk Check and pilot
upload metrics count only `DEALER_PAPERWORK`). `POST .../trade/carry-over` joins a fresh Desk Check case to the
journey's trade. Known limits: carrying a trade from a case without a journey copies the vehicle and payoff but
not the written offers; "analysis outdated" is computed from `changed_at`, so removing the whole trade does not
raise it; analysis does not use trade data until tickets 101 and 103.

- [x] A Trade Vehicle has VIN, decoded year, make, model and trim, mileage, and a condition choice with a plain
      description of each level. An invalid VIN is refused with the checksum message from ticket 52.
- [x] "Do you still owe money on it?" is required before the trade is complete. Yes asks for lender, payoff
      amount and good-through date; "I don't know" is allowed and leaves the payoff unknown, never zero.
- [x] A payoff past its good-through date is marked stale wherever it is used, with a prompt to refresh it.
- [x] Written trade offers record amount, source, offer date, expiry and an evidence document that goes
      through the ordinary intake pipeline. Expired offers stay visible but are not used for comparison.
- [x] No feature requests, arranges, sells or refers trade offers, and no trade data is shared with any
      dealer or buyer of cars. Copy says the platform does not buy cars or send the trade anywhere.
- [x] The trade carries into every case in the journey, including the fresh case from the Desk Check's
      "Start a fresh case for this car"; a change after a case was analyzed marks that analysis as outdated.
- [x] Trade and payoff data are exported with the account and deleted with the journey, case and account
      (tickets 16, 17, 88).
- [x] The trade panel works one-handed at 360 px in both themes and is registered in `Accessibility.test.tsx`.

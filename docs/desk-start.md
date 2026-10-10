# Start at the dealer (desk-start v1)

"I'm at the dealer" is a camera control in the header. One tap opens the phone's camera
(`<input type="file" capture="environment">`, labelled by the control itself). The photos go
through the ordinary `POST /documents` intake of the open case, or of a new case started with
two ZIP codes and the payment method when no case is open. Nothing is read on the device and
there is no separate upload path.

## Routing: `GET /api/v1/cases/{caseId}/desk-start?documentId=`

Case credentials, as for any case resource. Without `documentId` it uses the case's latest dealer
paperwork, and once an Agreed Price exists only paperwork added after it, as the Desk Check does.
It shares the report purchase gate once an Agreed Price exists. It reads only; it compares nothing.

| `status` | When |
| --- | --- |
| `AWAITING_PAPERWORK` | No dealer paperwork yet |
| `READING` | Uploaded, extraction not finished |
| `UNREADABLE` | Processing failed, or nothing usable (no VIN, no amount) was read |
| `READ` | A VIN or an amount was read |

| `route` | When |
| --- | --- |
| `AGREED_CAR` | The VIN read matches the Agreed Price's car |
| `THIS_CASE` | No Agreed Price, and the case has no car yet or the same VIN |
| `FRESH_CASE` | Another VIN; `freshCase` carries the VIN, dealer ZIP and payment method |
| `CHOOSE` | No VIN read, and the case already has a car; the Buyer says which |

`questions` says what the paperwork cannot answer:

- `trade.ask`: `NONE` when the journey's (or case's) Trade Vehicle is recorded and complete,
  which is then reused as `recorded`; `OWED` when it is recorded without the owed answer;
  `DETAILS` when the paperwork shows a trade allowance or payoff that is not recorded; otherwise
  `WHETHER`. Answers are saved through the existing `PUT /trade`.
- `financing.ask`: only when the paperwork shows a loan (APR, term, payment or amount financed)
  and the case has no saved outside financing. The Buyer's own APR is used for the first result
  and is not saved.

The client confirms the route with the Buyer before asking for a result. Confirming a car for a
case without one records it through `POST /vehicle-evidence`. A fresh case is started through
`POST /api/v1/cases` (the Buyer's ZIP is asked again; it is not stored on the earlier case), gets
the VIN, joins the earlier case's trade through `POST /trade/carry-over`, and is sent the same
photos through intake. The paperwork also stays in the case it was first sent to.

## First result: `POST /api/v1/cases/{caseId}/desk-start/result`

```json
{ "route": "AGREED_CAR | THIS_CASE", "documentId": "…", "typed": { … }, "ownAprPercent": 5.9 }
```

`route` is required, so nothing is compared before the Buyer confirms. `AGREED_CAR` paperwork
whose VIN differs from the agreed car is refused (409 `DIFFERENT_VIN`). `typed` replaces the
document: the four-square's selling price, trade allowance, down payment, monthly payment,
term, APR, out-the-door total, amount financed and VIN, any of them. Typed numbers are compared
for this answer and not stored; against an Agreed Price only the typed terms are compared.

The result leads with `outTheDoor.amountMinor` (null when the paperwork shows none, never zero)
and its difference from the Agreed Price, then `lead`, the finding with the largest calculable
dollar effect, each with a `deskQuestion` for the Buyer to ask. Rules, `desk-start.v1`:

- Against the Agreed Price (`RevisedOfferPolicy` over the terms as read or typed): an added
  charge, a removed credit, a higher charge, selling price, down payment or payment, a smaller
  discount, rebate or trade allowance, a higher APR (priced on the paperwork's amount financed
  and term) and a changed term.
- Without one: each dealer add-on and dealer fee line as read.
- Both: no out-the-door total (or a payment shown without one), and a dealer APR above the
  Buyer's own lender's APR, priced as the difference in scheduled payments through
  `FinancingCalculator`.

Nothing is verified: the source is `READ_FROM_PAPERWORK` or `TYPED_BY_BUYER`, and the full Desk
Check, verification and analysis remain where they were. Ticket 104 adds the named desk tactics,
and ticket 103 the trade allowance and payoff checks.

## Measuring upload-to-first-result on a phone

The client marks `desk-start:photo` when the photo is chosen and measures `desk-start:read`
(photo to the routing question) and `desk-start:first-result` (photo to the result on screen,
including the Buyer's own answers). Both also appear on the result as `data-read-ms` and
`data-first-result-ms`.

1. On a phone on cellular data only (Wi-Fi off), open the deployed site with Safari Web
   Inspector or Chrome remote debugging attached.
2. Open the agreed car's case, tap "I'm at the dealer" and photograph a text-bearing four-square.
3. Read `performance.getEntriesByName("desk-start:read")` and `"desk-start:first-result"` in
   the console, or the two attributes on `.dealer-result`. Record the device, network, signal,
   file size and both numbers. Repeat at least ten times for a p95.

Photos are read by the Claude extraction provider only once ticket 36 enables it; until then a
photo of paper is `UNREADABLE` and the typed four-square is the path.

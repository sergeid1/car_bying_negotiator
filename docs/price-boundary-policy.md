# Price boundaries v1

The policy uses the frozen Market Assessment low, midpoint (rounded down to a minor
unit), and high as opening, target, and policy maximum selling prices. These are
estimated negotiation suggestions, not a forecast or a guaranteed saving. Savings
use the snapshotted Dealer Offer's gross selling price as their named baseline.

OTD conversion holds the offer's other charges, reductions, and trade terms fixed.
It replaces selling price in the recorded taxable charges, applies the recorded
trade tax credit, clamps taxable basis to zero, and recalculates tax using the
recorded rate and rounding rule. Unknown charges or tax treatment disable conversion.
Buyer selling-price and OTD limits remain independent and both apply. Each available
walk-away ceiling is the lower of its policy maximum and its Buyer maximum.

POST `/api/v1/cases/{caseId}/price-plans` accepts optional
`maxSellingPriceMinor` and `maxOutTheDoorPriceMinor`. Missing limits inherit case
limits. POST `/approved-baselines` accepts `planVersion` and `approved: true`.
Both require Case-Access-Token and Idempotency-Key headers. GET either collection
with `/latest` or `/{version}` returns the frozen version. Reusing a key with a
different body is a conflict. Only explicit approval creates a baseline and its
Buyer audit event. Refreshing the market or proposing another plan never changes
an approved version. Changed offer or vehicle evidence requires a new proposal.

Plans and baselines use immutable JPA snapshots without entity associations or
cascades; case locking serializes version allocation. Database constraints enforce
version and idempotency uniqueness. Audit events are immutable JPA entities.

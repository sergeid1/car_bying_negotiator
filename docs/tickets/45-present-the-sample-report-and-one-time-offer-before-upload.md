# 45 — Present the sample report and the one-time offer before upload

**What to build:** The client side of ticket 32 that needs no payment provider and no permissioned
case. Before uploading anything, a prospective Buyer can open a labelled example report and can see
the price, what is free and what is paid, refund terms, scope limits, the pilot jurisdiction, and the
excluded purchase types. At the payment point before the report, the Buyer buys the report and it
opens. Ticket 32 keeps the redacted real sample, the real payment provider, and enabling either.

**Blocked by:** 29 — Lead the report with the total, the questions, and the next action. Built against
the shared interface in ticket 44 with mocked responses; ticket 44 does not block this ticket.

**Status:** ready-for-agent

- [ ] The start of the flow links to the example report, reachable without starting a case. It renders
      `GET /api/v1/sample-report` through the existing report components, is labelled as an example
      built from a synthetic case, states the redaction method, and shows removed fields and absent
      document evidence as removed, never as blanks or errors.
- [ ] When `GET /api/v1/offer` is `AVAILABLE`, the intake screen states before any upload what is free
      and what requires payment, the one-time price, refund terms, scope limits, the pilot jurisdiction
      (or that none is selected yet), and the excluded purchase types, worded by the client from the
      codes. When it is `UNAVAILABLE` or fails, no price or paywall appears and today's flow is unchanged.
- [ ] At the payment point, a `402 PURCHASE_REQUIRED` from a report request (or `PURCHASE_REQUIRED`
      entitlement) shows the offer with a buy action instead of an error. Buying posts a purchase with
      an idempotency key, retries reuse that key, and a `PAID` result opens the report. `PENDING` with a
      `checkoutUrl` navigates there; `OFFER_CHANGED` reloads the offer and asks again.
- [ ] A `DEVELOPMENT_NO_CHARGE` checkout is visibly labelled as a development checkout that charges
      nothing.
- [ ] Copy avoids "AI negotiator" and "safe to sign", attributes no savings to the product, describes a
      verified quote change as a quote change, and never describes the price as a subscription or a
      success fee. `PilotCopyClaimsTest` passes, including identifiers and comments.
- [ ] Case recovery, document export, and deletion remain reachable without purchase.
- [ ] Every new panel is registered in `Accessibility.test.tsx`, and a Playwright spec proves the offer
      terms, the example report, and the payment point at 360 px without horizontal overflow.

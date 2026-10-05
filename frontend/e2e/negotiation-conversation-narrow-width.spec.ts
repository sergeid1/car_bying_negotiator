import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";

const LONG_DEALER = "internet-sales-department.after-hours@a-very-long-dealership-domain-name.example";
const pass = { pass: true, gateVersion: "outbound-gate.v2", reasons: [] };
const blocked = { pass: false, gateVersion: "outbound-gate.v2", reasons: [{ code: "COMMITMENT", text: "This wording may commit you to buying, paying, reserving or signing. Ask for written terms instead." }] };
const counter = "Would you consider USD 18500.00 as the selling price in a revised written offer?";

function conversation(state: string) {
  return {
    negotiationState: state === "TARGET_REACHED" ? "AWAITING_BUYER" : state, targetReached: state === "TARGET_REACHED",
    mandate: { version: 2, currency: "USD", targetMinor: 1850000, ceilingMinor: 1950000, expiresAt: "2026-10-18T12:00:00Z" },
    conversations: [{ quoteRequestId: "q1", dealerAddress: LONG_DEALER }],
    entries: [
      { kind: "QUOTE_REQUEST", at: "2026-10-04T12:00:00Z", acceptance: null, counteroffer: null, message: null,
        quoteRequest: { id: "q1", dealerAddress: LONG_DEALER, state: "SENT", gatedDraft: { draft: {
          text: "Could you provide an itemized out-the-door quote for VIN 1HGCM82633A004352 (listing: https://dealer.example/vehicle/a-very-long-listing-path-that-does-not-wrap-naturally-1234567890), including the selling price, taxes, all fees and all add-ons?" }, decision: pass } } },
      { kind: "OUTBOUND_MESSAGE", at: "2026-10-04T12:01:00Z", quoteRequest: null, acceptance: null, counteroffer: null,
        message: { id: "m1", direction: "OUTBOUND", dealerAddress: LONG_DEALER, deliveryState: "DELIVERED", createdAt: "2026-10-04T12:01:00Z",
          completedAt: "2026-10-04T12:02:00Z", contentAvailable: true, subject: "Request for an itemized vehicle quote",
          body: "Could you provide an itemized out-the-door quote?\n\n--\nTo stop all messages from this address, reply STOP or open: https://dealx.example/api/v1/relay/opt-out/abcdefghijklmnopqrstuvwxyz0123456789abcdefghijklmnop", attachments: [] } },
      { kind: "INBOUND_MESSAGE", at: "2026-10-04T13:00:00Z", quoteRequest: null, acceptance: null, counteroffer: null,
        message: { id: "m2", direction: "INBOUND", dealerAddress: LONG_DEALER, deliveryState: "RECEIVED", createdAt: "2026-10-04T13:00:00Z",
          completedAt: "2026-10-04T13:00:00Z", contentAvailable: true, subject: "Quote", body: "Selling price $25,000; OTD $26,800. ".repeat(6),
          attachments: [{ ordinal: -1, documentId: "doc1", outcome: "ACCEPTED", offerVersion: 2 }, { ordinal: 0, documentId: null, outcome: "MALWARE_DETECTED", offerVersion: null }] } },
      { kind: "OUTBOUND_MESSAGE", at: "2026-10-04T13:30:00Z", quoteRequest: null, acceptance: null, counteroffer: null,
        message: { id: "m3", direction: "OUTBOUND", dealerAddress: LONG_DEALER, deliveryState: "DELIVERY_UNKNOWN", createdAt: "2026-10-04T13:30:00Z",
          completedAt: null, contentAvailable: true, subject: "Revised itemized written offer", body: counter, attachments: [],
          deliveryCheckOffered: true, retryOffered: false } },
      { kind: "OUTBOUND_MESSAGE", at: "2026-10-04T13:40:00Z", quoteRequest: null, acceptance: null, counteroffer: null,
        message: { id: "m4", direction: "OUTBOUND", dealerAddress: LONG_DEALER, deliveryState: "FAILED", createdAt: "2026-10-04T13:40:00Z",
          completedAt: "2026-10-04T13:40:00Z", contentAvailable: true, subject: "Revised itemized written offer", body: counter, attachments: [],
          deliveryCheckOffered: false, retryOffered: true } },
      { kind: "COUNTEROFFER", at: "2026-10-04T14:00:00Z", quoteRequest: null, acceptance: null, message: null,
        counteroffer: { id: "n1", quoteRequestId: "q1", dealerAddress: LONG_DEALER, state: state === "AWAITING_BUYER" ? "PROPOSED" : "CANCELLED",
          cancelReason: state === "AWAITING_BUYER" ? null : state === "TARGET_REACHED" ? "TARGET_REACHED" : "PAUSED",
          proposalMinor: 1850000, reportVersion: 3, offerVersion: 2, grounds: [], messageId: null, createdAt: "2026-10-04T14:00:00Z", revisions: [
            { revision: 1, editedByBuyer: false, text: `${counter}\n\nCould you provide a current itemized written offer?`, decision: pass, createdAt: "2026-10-04T14:00:00Z" },
            { revision: 2, editedByBuyer: true, text: "I will buy it today if you can do better.", decision: blocked, createdAt: "2026-10-04T14:05:00Z" }] } },
    ],
  };
}

for (const state of ["AWAITING_BUYER", "PAUSED", "TARGET_REACHED"]) {
  test(`Negotiation conversation ${state} fits 360 px`, async ({ page }) => {
    await page.route("**/api/v1/**", route => route.fulfill({ status: 204, body: "" }));
    await page.route("**/api/v1/intake-options", route => route.fulfill({ json: { supported: ["US-NC"], consentPolicies: {
      serviceProcessing: "service-processing.v1", humanReview: "human-review.v1", anonymizedResearch: "anonymized-research.v1",
    } } }));
    await page.route("**/api/v1/cases", route => route.fulfill({ status: 201, json: { case: { id: "case-1" }, accessToken: "secret" } }));
    await page.route("**/api/v1/cases/case-1/vehicle-evidence", route => route.fulfill({ json: { identityConflicts: [], reliableForAnalysis: true } }));
    await page.route("**/api/v1/cases/case-1/quote-requests", route => route.fulfill({ json: {
      negotiationState: state === "TARGET_REACHED" ? "AWAITING_BUYER" : state, dealers: [],
      requests: [{ id: "q1", dealerAddress: LONG_DEALER, state: "SENT",
        gatedDraft: { draft: { text: "Could you provide a current itemized written offer?" }, decision: pass } }],
      documents: [{ documentId: "doc1", outcome: "ACCEPTED", offerVersion: 2, sourceKind: "MESSAGE_BODY", messageId: "m2" }],
    } }));
    await page.route("**/api/v1/cases/case-1/negotiation-conversation", route => route.fulfill({ json: conversation(state) }));
    await page.goto("/");
    await page.getByLabel(/your zip code/i).fill("27601");
    await page.getByLabel(/dealer zip code/i).fill("27604");
    await page.getByLabel(/^vin$/i).fill("1HGCM82633A004352");
    await page.getByRole("button", { name: /start my case/i }).click();
    const panel = page.locator(".negotiation-conversation");
    await expect(panel.getByRole("heading", { name: "Negotiation conversation" })).toBeVisible();
    await expect(panel.getByRole("list", { name: "Conversation, oldest first" })).toBeVisible();
    await expect(panel.getByRole("button", { name: /^Check delivery of the message to/ })).toBeVisible();
    await expect(panel.getByRole("button", { name: /^Retry the message to/ })).toBeVisible();
    if (state === "AWAITING_BUYER") {
      await expect(panel.getByRole("button", { name: "Approve revision 2 and send" })).toBeDisabled();
      await panel.getByRole("button", { name: "Edit this draft" }).click();
      await expect(panel.getByRole("textbox", { name: "Revised text" })).toBeVisible();
    } else if (state === "PAUSED") {
      await expect(panel.getByRole("button", { name: "Resume negotiation" })).toBeVisible();
    } else {
      await expect(panel.getByText(/at or below your target/)).toBeVisible();
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
    const results = await new AxeBuilder({ page }).include(".negotiation-conversation").withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"]).analyze();
    expect(results.violations.map(violation => ({ id: violation.id, nodes: violation.nodes.map(node => node.html) }))).toEqual([]);
  });
}

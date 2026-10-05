import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";

for (const state of ["ACCEPTANCE", "WRITTEN_CONFIRMATION", "AGREED", "BLOCKED", "JOURNEY_OPTIONS"]) {
  test(`Agreed Price ${state} fits 360 px`, async ({ page }) => {
    await page.route("**/api/v1/**", route => route.fulfill({ status: 204, body: "" }));
    await page.route("**/api/v1/intake-options", route => route.fulfill({ json: { supported: ["US-NC"], consentPolicies: {
      serviceProcessing: "service-processing.v1", humanReview: "human-review.v1", anonymizedResearch: "anonymized-research.v1",
    } } }));
    await page.route("**/api/v1/cases", route => route.fulfill({ status: 201, json: { case: { id: "case-1" }, accessToken: "secret" } }));
    await page.route("**/api/v1/cases/case-1/vehicle-evidence", route => route.fulfill({ json: { identityConflicts: [], reliableForAnalysis: true } }));
    await page.route("**/api/v1/cases/case-1/quote-requests", route => route.fulfill({ json: {
      negotiationState: state === "AGREED" ? "AGREED" : "AWAITING_BUYER", dealers: [],
      requests: [{ id: "q1", dealerAddress: "internet-sales-department.after-hours@a-very-long-dealership-domain-name.example", state: "SENT",
        gatedDraft: { draft: { text: "Could you provide a current itemized written offer?" }, decision: { pass: true, gateVersion: "v2", reasons: [] } } }],
      documents: [{ documentId: "d1", outcome: "ACCEPTED", offerVersion: 3, sourceKind: "MESSAGE_BODY", messageId: "m1" }],
    } }));
    await page.route("**/api/v1/cases/case-1/quote-acceptance", route => route.fulfill({ json: {
      requests: [{ id: "a1", kind: state === "WRITTEN_CONFIRMATION" ? state : "ACCEPTANCE", state: state === "WRITTEN_CONFIRMATION" ? "SENT" : "DRAFT",
        offerVersion: 3, planVersion: 2, gatedDraft: { draft: { text: "The Buyer accepts your itemized quote for VIN 1HGCM82633A004352 with an out-the-door total of USD 26800.00, received on 2026-10-04 (UTC), subject to a test drive and the final paperwork matching that quote." },
          decision: { pass: state !== "BLOCKED", gateVersion: "outbound-gate.v2", reasons: state === "BLOCKED" ? [{ code: "NO_ACTIVE_MANDATE", text: "Approve a current mandate." }] : [] } } }],
      agreedPrice: state === "AGREED" || state === "JOURNEY_OPTIONS" ? { offerVersion: 3, baselineVersion: 2, actor: "BUYER", acceptedAt: "2026-10-04T12:00:00Z",
        journey: { journeyLinked: state === "JOURNEY_OPTIONS", otherOpenNegotiations: state === "JOURNEY_OPTIONS" ? [{ caseId: "other", label: "The other dealer with a long conversation label" }] : [] } } : null,
    } }));
    await page.goto("/");
    await page.getByLabel(/your zip code/i).fill("27601");
    await page.getByLabel(/dealer zip code/i).fill("27604");
    await page.getByLabel(/^vin$/i).fill("1HGCM82633A004352");
    await page.getByRole("button", { name: /start my case/i }).click();
    const panel = page.locator(".agreed-price-panel");
    await expect(panel.getByRole("heading", { name: "Accept a written quote" })).toBeVisible();
    await expect(panel.getByRole("button", { name: "Refresh acceptance status" })).toBeVisible();
    if (state === "AGREED" || state === "JOURNEY_OPTIONS") await expect(panel.getByRole("button", { name: "Download one-page Agreed Price summary" })).toBeVisible();
    else if (state === "WRITTEN_CONFIRMATION") await expect(panel.getByText(/Written confirmation requested/)).toBeVisible();
    else await expect(panel.getByRole("button", { name: "Approve this acceptance text and baseline" })).toBeVisible();
    if (state === "JOURNEY_OPTIONS") {
      await expect(panel.getByRole("button", { name: "Pause The other dealer with a long conversation label" })).toBeVisible();
      await expect(panel.getByRole("button", { name: "Stop The other dealer with a long conversation label" })).toBeVisible();
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
    const results = await new AxeBuilder({ page }).include(".agreed-price-panel").withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"]).analyze();
    expect(results.violations.map(violation => ({ id: violation.id, nodes: violation.nodes.map(node => node.html) }))).toEqual([]);
  });
}

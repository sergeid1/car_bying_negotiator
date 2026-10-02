import AxeBuilder from "@axe-core/playwright";
import { expect, Page, test } from "@playwright/test";
const report = { version: 1, asOf: "2026-09-30", status: "COMPLETE", analysis: {
  marketEvidence: { status: "AVAILABLE", paragraphs: ["Market data retrieved: 2026-09-30T12:00:00Z.",
    "Observation window: 2026-09-01T12:00:00Z to 2026-09-28T12:00:00Z.",
    "Uncertainty: medium confidence from 4 usable comparables. This is an estimated selling-price range.",
    "The usable sample is sparse; the range includes a $500 uncertainty buffer."] },
  reviewLabel: "Review required", dealRating: "FAIR", ratingBasis: "Recorded evidence", nextAction: "Review the fees",
  topReasons: ["The verified offer requires review against your approved price limits.", "Market selling-price evidence is available; it is an estimate.", "The quoted total needs reconciliation with recorded charges."], marketAvailability: "AVAILABLE", cashAvailability: "AVAILABLE", materialAmounts: [{ label: "/result/outTheDoorTotal", minorUnits: 2850000, currency: "USD", precision: "ESTIMATED", source: "cashEconomics", pointer: "/result/outTheDoorTotal" }], policyVersions: {}, limitations: ["Market evidence is estimated."],
}, evidence: {}, reproduction: {} };
async function api(page: Page, unavailable = false) {
  await page.route("**/api/v1/**", async route => {
    const path = new URL(route.request().url()).pathname;
    if (path === "/api/v1/cases/current") {
      expect(route.request().headers()["case-access-token"]).toBe("private-secret");
      return route.fulfill({ status: unavailable ? 404 : 200, json: unavailable ? { detail: "Buying Case not found" } : { id: "current" } });
    }
    const body = path.endsWith("negotiation") ? { language: { draft: "Review the fees", items: [] } } : path.endsWith("dealer-offers/latest") ? { version: 1, status: "VERIFIED" }
      : path.endsWith("analyses") ? { operation: { id: "op", stage: "QUEUED" } }
      : path.endsWith("operations/op") ? { stage: "COMPLETE" }
      : path.endsWith("reports/latest") ? report
      : path.endsWith("document-limits") ? { acceptedMediaTypes: ["application/pdf"], maxFileBytes: 20971520, maxPages: 25, maxPixels: 40000000, minWidth: 1, minHeight: 1 }
      : path.endsWith("intake-options") ? { supported: ["US-NC"], consentPolicies: {} }
      : {};
    await route.fulfill({ json: body });
  });
}
test("saved link opens frozen offer analysis and report in a second browser session at 360 px", async ({ page, browser }) => {
  await api(page);
  await page.goto("/#case=current&access=private-secret");
  await expect(page.getByRole("heading", { name: "Deal Analysis Report", exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Deal Analysis Report", exact: true })).toBeFocused();
  const lead = page.getByRole("region", { name: "Total, findings and next action" });
  const box = await lead.boundingBox();
  expect(box!.y).toBeGreaterThanOrEqual(0);
  expect(box!.y + box!.height).toBeLessThanOrEqual(780);
  await page.keyboard.press("Tab");
  await expect(page.getByRole("link", { name: "$28,500.00" })).toBeFocused();
  expect(new URL(page.url()).hash).toBe("");
  const link = await page.getByRole("link", { name: "Private case link", exact: true }).getAttribute("href");
  const session = await browser.newContext({ viewport: { width: 360, height: 800 } });
  const second = await session.newPage(); await api(second);
  await second.goto(link!);
  await expect(second.getByRole("heading", { name: "Deal Analysis Report", exact: true })).toBeVisible();
  const market = second.getByRole("region", { name: "Market evidence", exact: true });
  await expect(market).toContainText("Market data retrieved:");
  await expect(market).toContainText("Observation window:");
  await expect(market).toContainText("Uncertainty: medium confidence");
  expect(await second.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  expect((await new AxeBuilder({ page: second }).withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"]).analyze()).violations).toEqual([]);
  await session.close();
});
test("expired or revoked link exposes only generic failure and email recovery", async ({ page }) => {
  await api(page, true); await page.goto("/#case=current&access=private-secret");
  await expect(page.getByRole("alert")).toHaveText(/may have expired, been revoked, or be unrecognized/);
  await expect(page.getByRole("button", { name: "Email recovery link", exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Deal Analysis Report", exact: true })).toHaveCount(0);
  await expect(page.getByLabel("Buying Case ID", { exact: true })).toHaveCount(0);
  expect(new URL(page.url()).hash).toBe("");
});

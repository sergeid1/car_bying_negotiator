import AxeBuilder from "@axe-core/playwright";
import { expect, Page, test } from "@playwright/test";
const report = { version: 1, asOf: "2026-09-30", status: "COMPLETE", analysis: {
  reviewLabel: "Review required", dealRating: "FAIR", ratingBasis: "Recorded evidence", nextAction: "Review the fees",
  topReasons: ["Review recorded fees"], marketAvailability: "AVAILABLE", cashAvailability: "AVAILABLE", materialAmounts: [], policyVersions: {}, limitations: ["Market evidence is estimated."],
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
  expect(new URL(page.url()).hash).toBe("");
  const link = await page.getByRole("link", { name: "Private case link", exact: true }).getAttribute("href");
  const session = await browser.newContext({ viewport: { width: 360, height: 800 } });
  const second = await session.newPage(); await api(second);
  await second.goto(link!);
  await expect(second.getByRole("heading", { name: "Deal Analysis Report", exact: true })).toBeVisible();
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

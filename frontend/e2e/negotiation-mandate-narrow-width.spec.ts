import AxeBuilder from "@axe-core/playwright";
import { expect, Page, test } from "@playwright/test";

const OPTIONS = {
  supported: ["US-NC"],
  consentPolicies: {
    serviceProcessing: "service-processing.v1",
    humanReview: "human-review.v1",
    anonymizedResearch: "anonymized-research.v1",
  },
};

const PROPOSAL = {
  available: true, unavailableReason: null, planVersion: 1, currency: "USD", targetMinor: 2_300_000, ceilingMinor: 2_400_000,
  disclosures: ["PAYING_CASH", "HAS_OUTSIDE_FINANCING", "FIRST_NAME", "PURCHASE_TIMELINE"],
};
const MANDATE = {
  version: 2, status: "ACTIVE", currency: "USD", targetMinor: 2_250_000, ceilingMinor: 2_350_000, planCeilingMinor: 2_400_000,
  disclosures: ["PAYING_CASH", "HAS_OUTSIDE_FINANCING", "FIRST_NAME", "PURCHASE_TIMELINE"], autonomyMode: "SUPERVISED",
  expiresAt: "2026-10-17T12:00:00Z", approvedAt: "2026-10-03T12:00:00Z", revokedAt: null,
};

/** The layout gate needs no real service, only the shapes the mandate panel renders from. */
async function stubCaseApi(page: Page, proposal: object, mandates: object[]) {
  await page.route("**/api/v1/**", route => route.fulfill({ status: 204, body: "" }));
  await page.route("**/api/v1/intake-options", route => route.fulfill({ json: OPTIONS }));
  await page.route("**/api/v1/cases", route =>
    route.fulfill({ status: 201, json: { case: { id: "case-1" }, accessToken: "case-secret" } }));
  await page.route("**/api/v1/cases/case-1/vehicle-evidence", route =>
    route.fulfill({ json: { identityConflicts: [], reliableForAnalysis: true } }));
  await page.route("**/api/v1/cases/case-1/mandates/proposal", route => route.fulfill({ json: proposal }));
  await page.route("**/api/v1/cases/case-1/mandates", route => route.fulfill({ json: mandates }));
}

async function openCase(page: Page) {
  await page.goto("/");
  await page.getByLabel(/your zip code/i).fill("27601");
  await page.getByLabel(/dealer zip code/i).fill("27604");
  await page.getByLabel(/^vin$/i).fill("1HGCM82633A004352");
  await page.getByRole("button", { name: /start my case/i }).click();
  await expect(page.getByRole("heading", { name: "Approve a Negotiation Mandate" })).toBeVisible();
}

async function expectNoOverflowOrViolations(page: Page) {
  const overflow = await page.evaluate(() => ({
    documentWidth: document.documentElement.scrollWidth,
    viewportWidth: document.documentElement.clientWidth,
    widest: Array.from(document.querySelectorAll<HTMLElement>("body *"))
      .filter(element => element.getBoundingClientRect().right > document.documentElement.clientWidth + 1)
      .map(element => `${element.tagName.toLowerCase()}.${element.className}`)
      .slice(0, 5),
  }));
  expect(overflow.widest).toEqual([]);
  expect(overflow.documentWidth).toBeLessThanOrEqual(overflow.viewportWidth);
  const results = await new AxeBuilder({ page })
    .include(".mandate-panel")
    .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
    .analyze();
  expect(results.violations.map(violation => ({ id: violation.id, nodes: violation.nodes.map(node => node.html) }))).toEqual([]);
}

test("the mandate terms, current version, approval form and history fit 360 px", async ({ page }) => {
  await stubCaseApi(page, PROPOSAL, [MANDATE, { ...MANDATE, version: 1, status: "REVOKED", revokedAt: "2026-10-03T13:00:00Z" }]);
  await openCase(page);
  const panel = page.locator(".mandate-panel");
  await expect(panel.getByRole("button", { name: "Revoke mandate now" })).toBeVisible();
  await expect(panel.getByRole("button", { name: "Approve mandate" })).toBeVisible();
  await expect(panel.getByRole("heading", { name: "What the platform may not do" })).toBeVisible();
  await expectNoOverflowOrViolations(page);
});

test("an unavailable mandate explains why at 360 px", async ({ page }) => {
  await stubCaseApi(page, { ...PROPOSAL, available: false, planVersion: null, targetMinor: null, ceilingMinor: null,
    unavailableReason: "A Negotiation Mandate needs a market check for this car first. Run the market assessment, then review price boundaries." }, []);
  await openCase(page);
  await expect(page.locator(".mandate-panel").getByText(/needs a market check/)).toBeVisible();
  await expectNoOverflowOrViolations(page);
});

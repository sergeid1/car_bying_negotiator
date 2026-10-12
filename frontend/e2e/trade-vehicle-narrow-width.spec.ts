import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";
import { openCaseStep } from "./case-steps";

const conditions = [
  { value: "EXCELLENT", label: "Excellent", description: "Looks and drives like new. No dents, warning lights or repairs needed, and a clean title." },
  { value: "GOOD", label: "Good", description: "Light wear such as small scratches or chips. Drives well and needs no repairs right now." },
  { value: "FAIR", label: "Fair", description: "Clear wear or cosmetic damage, or it needs a repair soon, such as tires or brakes. It still drives." },
  { value: "POOR", label: "Poor", description: "Major damage, mechanical problems, a warning light that stays on, or a branded title such as salvage or flood." },
];
const trade = {
  id: "trade-1", revision: 2,
  vehicle: { vin: "5J6RW2H52ML000002", decodeOutcome: "DECODED", source: "Decoder", decoderVersion: "v1", decodedAt: "2026-10-01T12:00:00Z",
    year: "2021", make: "Honda", model: "CR-V", trim: "EX" },
  mileage: 61000, condition: "GOOD", moneyOwed: "YES", lender: "First Coast Community Credit Union of Northeast Florida",
  payoffMinor: 1825000, payoffGoodThrough: "2026-10-01", payoffUnknown: false, payoffStale: true, complete: true,
  changedAt: "2026-10-05T12:00:00Z",
  offers: [
    { id: "old", amountMinor: 1500000, source: "Coastal Auto Buyers and Trade Appraisal Center", offerDate: "2026-09-10",
      expiresOn: "2026-09-17", expired: true, usedForComparison: false, evidence: { documentId: "d1", state: "READY", pageCount: 1 },
      createdAt: "2026-09-10T12:00:00Z" },
    { id: "new", amountMinor: 1400000, source: "Sunshine Car Exchange", offerDate: "2026-10-04", expiresOn: "2026-10-11",
      expired: false, usedForComparison: true, evidence: { documentId: "d2", state: "READY", pageCount: 1 }, createdAt: "2026-10-04T12:00:00Z" },
  ],
  bestOfferId: "new",
};

async function openCase(page: Page, view: unknown) {
  await page.route("**/api/v1/**", route => route.fulfill({ status: 204, body: "" }));
  await page.route("**/api/v1/intake-options", route => route.fulfill({ json: { supported: ["US-NC"], consentPolicies: {
    serviceProcessing: "service-processing.v1", humanReview: "human-review.v1", anonymizedResearch: "anonymized-research.v1",
  } } }));
  await page.route("**/api/v1/cases", route => route.fulfill({ status: 201, json: { case: { id: "case-1" }, accessToken: "secret" } }));
  await page.route("**/api/v1/cases/case-1/vehicle-evidence", route => route.fulfill({ json: { identityConflicts: [], reliableForAnalysis: true } }));
  await page.route("**/api/v1/cases/case-1/trade", route => route.fulfill({ json: view }));
  await page.goto("/");
  await page.getByLabel(/your zip code/i).fill("27601");
  await page.getByLabel(/dealer city/i).fill("27604");
  await page.getByLabel("Dealer state").selectOption("NC");
  await page.getByLabel(/^vin$/i).fill("1HGCM82633A004352");
  await page.getByRole("button", { name: /start my case/i }).click();
    await openCaseStep(page, "At the dealer");
  const panel = page.locator(".trade-vehicle");
  await expect(panel.getByRole("heading", { name: "Your trade-in" })).toBeVisible();
  return panel;
}

async function expectUsableAtNarrowWidth(page: Page, panel: ReturnType<Page["locator"]>) {
  // Let the theme cross-fade and entrance animation settle before measuring colours and layout.
  await page.waitForTimeout(600);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
  for (const control of await panel.locator("button, input:not([type=radio]):not([type=checkbox]), label:has(> input[type=radio])").all())
    if (await control.isVisible()) expect((await control.boundingBox())!.height).toBeGreaterThanOrEqual(44);
  const results = await new AxeBuilder({ page }).include(".trade-vehicle").withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"]).analyze();
  expect(results.violations.map(violation => ({ id: violation.id, nodes: violation.nodes.map(node => node.html) }))).toEqual([]);
}

// The narrow and narrow-dark projects run each state in both themes.
test("a recorded trade-in with a stale payoff and written offers works one-handed at 360 px", async ({ page }) => {
  const panel = await openCase(page, { holder: "JOURNEY", trade, conditions, analysis: { analyzedAt: "2026-10-03T12:00:00Z", outdated: true } });
  await expect(panel.getByText("2021 Honda CR-V EX")).toBeVisible();
  await expect(panel.getByText(/Stale payoff/)).toBeVisible();
  await expect(panel.getByText(/so that analysis is outdated/)).toBeVisible();
  await expect(panel.getByRole("list", { name: "Your written trade offers" }).getByRole("listitem").first())
    .toContainText("Expired: not used for comparison");
  await panel.getByRole("button", { name: "Add a written offer" }).click();
  await expect(panel.getByLabel("Photo or PDF of the offer")).toBeVisible();
  await expectUsableAtNarrowWidth(page, panel);
});

test("recording a trade-in that is still owed on works one-handed at 360 px", async ({ page }) => {
  const panel = await openCase(page, { holder: "CASE", trade: null, conditions, analysis: { analyzedAt: null, outdated: false } });
  await panel.getByRole("button", { name: "Add the car I'm trading in" }).click();
  await panel.getByLabel("VIN").fill("5J6RW2H52ML000002");
  await panel.getByLabel("Mileage").fill("61000");
  await panel.getByRole("radio", { name: /^Good/ }).check();
  await panel.getByRole("radio", { name: "Yes" }).check();
  await expect(panel.getByLabel("Lender")).toBeVisible();
  await expect(panel.getByLabel("Good through")).toBeVisible();
  await expect(panel.getByText(/Ask your lender for a payoff quote/)).toBeVisible();
  await expectUsableAtNarrowWidth(page, panel);
});

// Ticket 106: the free value checks, the estimates the Buyer typed back, and the estimate form.
test("the free trade value checks and the Buyer's estimates work one-handed at 360 px", async ({ page }) => {
  const estimates = [
    { id: "kbb", source: "Kelley Blue Book Private Party and Trade-In Values", lowMinor: 1300000, highMinor: null, checkedOn: "2026-08-01",
      old: true, createdAt: "2026-08-01T12:00:00Z" },
    { id: "carfax", source: "Carfax", lowMinor: 1400000, highMinor: 1600000, checkedOn: "2026-10-05", old: false, createdAt: "2026-10-05T12:00:00Z" },
  ];
  const panel = await openCase(page, { holder: "JOURNEY", trade: { ...trade, estimates, estimateInUseId: "carfax" }, conditions,
    analysis: { analyzedAt: null, outdated: false } });
  const checks = panel.locator(".trade-value-checks");
  await expect(checks.getByRole("link", { name: /Carfax value lookup/ })).toHaveAttribute("href", "https://www.carfax.com/value/");
  await expect(checks.getByRole("link", { name: /CarMax instant offer/ })).toHaveAttribute("target", "_blank");
  await expect(checks.getByRole("button", { name: "Copy VIN" })).toBeVisible();
  for (const link of await checks.getByRole("link").all()) expect((await link.boundingBox())!.height).toBeGreaterThanOrEqual(44);
  await expect(panel.getByRole("list", { name: "Your trade-in estimates" }).getByRole("listitem").first())
    .toContainText("Old: looked up more than 30 days ago");
  await panel.getByRole("button", { name: "Add an estimate I looked up" }).click();
  await panel.getByRole("radio", { name: "Another site" }).check();
  await panel.getByLabel("Site name").fill("CarMax");
  await expect(panel.getByText(/is a written offer, not an estimate/)).toBeVisible();
  await expect(panel.getByLabel("Date you looked it up")).toBeVisible();
  await expectUsableAtNarrowWidth(page, panel);
});

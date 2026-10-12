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

/** A one-pixel PNG stands in for a normalized page; the outline is positioned over whatever is shown. */
const PNG = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
  "base64");

const evidence = (pageNumber: number) => ({ pageNumber, x: 0.08, y: 0.1, width: 0.55, height: 0.04 });

const EXTRACTION = {
  id: "ext-1", documentId: "doc-1", version: 1,
  extractorVersion: "fixture-extractor.v1", modelVersion: "fixture-model.v1",
  candidates: [
    { id: "c1", field: "SELLING_PRICE", originalWording: "Selling price $25,000", normalizedValue: "2500000",
      normalizedUnit: "USD_MINOR", evidence: evidence(1), confidence: 0.98, material: true,
      verificationState: "UNVERIFIED", currentCorrection: null, correctionHistory: [] },
    { id: "c2", field: "AMOUNT_FINANCED", originalWording: "Amount financed 26,800.OO", normalizedValue: null,
      normalizedUnit: "USD_MINOR", evidence: evidence(2), confidence: 0.41, material: true,
      verificationState: "UNVERIFIED", currentCorrection: null, correctionHistory: [] },
    { id: "c3", field: "TRADE_ALLOWANCE", originalWording: "Trade allowance $8,000", normalizedValue: "800000",
      normalizedUnit: "USD_MINOR", evidence: evidence(2), confidence: 0.97, material: true,
      verificationState: "UNVERIFIED", currentCorrection: null, correctionHistory: [] },
  ],
  lineItems: [
    { id: "l1", originalLabel: "Mystery protection package with a very long printed label", category: "UNKNOWN",
      amountMinor: 89900, currency: "USD", evidence: evidence(2), confidence: 0.96,
      verificationState: "UNVERIFIED", currentCorrection: null, correctionHistory: [] },
  ],
};

const offer = (version: number, price: string) => ({
  id: `offer-${version}`, version, status: "VERIFIED", createdAt: "2026-09-30T10:00:00Z",
  terms: {
    candidates: [{ field: "SELLING_PRICE", value: price, unit: "USD_MINOR" }],
    lineItems: [{ originalLabel: "Mystery protection package with a very long printed label",
      category: "UNKNOWN", amountMinor: 89900, currency: "USD" }],
  },
});

/** The gate needs no real service, only the shapes the verification panel renders from. */
async function stubCaseApi(page: Page) {
  await page.route("**/api/v1/**", route => route.fulfill({ status: 204, body: "" }));
  await page.route("**/api/v1/intake-options", route => route.fulfill({ json: OPTIONS }));
  await page.route("**/api/v1/cases", route =>
    route.fulfill({ status: 201, json: { case: { id: "case-1" }, accessToken: "case-secret" } }));
  await page.route("**/api/v1/cases/case-1/vehicle-evidence", route =>
    route.fulfill({ json: { identityConflicts: [], reliableForAnalysis: true } }));
  await page.route("**/api/v1/cases/case-1/documents", route =>
    route.fulfill({ json: [{ id: "doc-1", originalFilename: "buyers-order-from-the-dealership.pdf" }] }));
  await page.route("**/api/v1/cases/case-1/documents/doc-1/extractions/latest", route =>
    route.fulfill({ json: EXTRACTION }));
  await page.route("**/api/v1/cases/case-1/documents/doc-1/pages/*/image", route =>
    route.fulfill({ contentType: "image/png", body: PNG }));
  await page.route("**/api/v1/cases/case-1/dealer-offers/latest", route => route.fulfill({ json: offer(2, "2450000") }));
  await page.route("**/api/v1/cases/case-1/dealer-offers/1", route => route.fulfill({ json: offer(1, "2500000") }));
}

async function openCase(page: Page) {
  await page.goto("/");
  await page.getByLabel(/your zip code/i).fill("27601");
  await page.getByLabel(/dealer city/i).fill("27604");
  await page.getByLabel("Dealer state").selectOption("NC");
  await page.getByLabel(/^vin$/i).fill("1HGCM82633A004352");
  await page.getByRole("button", { name: /start my case/i }).click();
  await expect(page.getByRole("heading", { name: /check the terms against the paperwork/i })).toBeVisible();
  await expect(page.getByRole("table", { name: /terms that differ/i })).toBeVisible();
}

test.beforeEach(async ({ page }) => {
  await stubCaseApi(page);
  await openCase(page);
});

test("terms, source pages, trade terms and version changes fit 360 px without horizontal scrolling", async ({ page }) => {
  await page.getByRole("button", { name: "Correct Amount financed" }).click();
  await expect(page.getByLabel(/amount financed as shown on page 2/i)).toBeVisible();
  await expect(page.getByRole("img", { name: /where selling price was read/i })).toBeVisible();

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
});

test("the source page sits below its value at 360 px, with the read region outlined inside it", async ({ page }) => {
  const item = page.getByRole("listitem", { name: /^selling price$/i });
  const value = await item.locator(".verify-term").boundingBox();
  const frame = await item.locator(".source-page-frame").boundingBox();
  const outline = await item.locator(".source-region").boundingBox();
  expect(frame!.y).toBeGreaterThanOrEqual(value!.y + value!.height - 1);
  expect(outline!.x).toBeGreaterThanOrEqual(frame!.x);
  expect(outline!.x + outline!.width).toBeLessThanOrEqual(frame!.x + frame!.width + 1);
  expect(outline!.y + outline!.height).toBeLessThanOrEqual(frame!.y + frame!.height + 1);
});

test("the verification view has no AA violations, including colour contrast", async ({ page }) => {
  const results = await new AxeBuilder({ page })
    .include(".verify-panel")
    .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
    .analyze();
  expect(results.violations.map(violation => ({
    id: violation.id,
    nodes: violation.nodes.map(node => node.html),
  }))).toEqual([]);
});

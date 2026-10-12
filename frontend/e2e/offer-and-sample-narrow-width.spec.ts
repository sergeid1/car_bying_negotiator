import AxeBuilder from "@axe-core/playwright";
import { expect, Page, test } from "@playwright/test";

const OFFER = {
  status: "AVAILABLE", offerId: "offer-1", kind: "ONE_TIME_PER_CASE", price: { amountMinor: 4900, currency: "USD" },
  paymentPoint: "BEFORE_REPORT",
  free: ["CASE_START", "VEHICLE_EVIDENCE", "DOCUMENT_UPLOAD", "TERM_VERIFICATION", "CASE_RECOVERY", "DOCUMENT_EXPORT", "CASE_DELETION"],
  paid: ["DEAL_ANALYSIS_REPORT", "REPORT_EXPORT", "REPORT_EXPLANATION", "NEGOTIATION_DRAFTS", "REPLY_ASSISTANCE", "REVISED_OFFER_REVIEW"],
  refund: { windowDays: 14, terms: "FULL_REFUND_ON_REQUEST_WITHIN_WINDOW" },
  jurisdiction: { code: "US-NC", name: "North Carolina" },
  excludedPurchaseTypes: ["NEW_VEHICLE", "LEASE", "PRIVATE_PARTY", "AUCTION", "MOTORCYCLE", "RV", "COMMERCIAL_VEHICLE"],
  scopeLimits: ["NO_DEALER_CONTACT", "NO_LEGAL_OR_TAX_CONCLUSIONS", "NO_VEHICLE_HISTORY", "NO_TRADE_IN_VALUATION", "NO_FINAL_CONTRACT_REVIEW"],
  checkout: "DEVELOPMENT_NO_CHARGE",
};

const REPORT = { version: 1, asOf: "2026-09-30", status: "COMPLETE", analysis: {
  reviewLabel: "Review required", dealRating: "FAIR", ratingBasis: "Selling price comparison", nextAction: "Ask the dealer to itemize the fees.",
  topReasons: ["The documentation fee is above the recorded range.", "Market selling-price evidence is available; it is an estimate.", null],
  marketAvailability: "AVAILABLE", cashAvailability: "AVAILABLE",
  materialAmounts: [
    { label: "Out-the-door total", minorUnits: 2847500, currency: "USD", precision: "EXACT", source: "cashEconomics",
      pointer: "/result/outTheDoorTotal", documentSource: null, sourceNote: null },
    { label: "Doc fee", minorUnits: 89900, currency: "USD", precision: "EXACT", source: "cashEconomics",
      pointer: "/result/lineItems/0/amount", documentSource: null, sourceNote: null },
  ],
  policyVersions: { cash: "cash.v1" }, limitations: ["Financial consistency only."],
}, evidence: { dealerOffer: { id: "o1" } }, reproduction: {} };

const SAMPLE = { label: "EXAMPLE", source: "SYNTHETIC_CASE",
  redaction: { method: "FIELD_CATEGORIES", version: "sample-redaction.v2", removed: ["VIN", "DEALER_IDENTITY", "LISTING_URL", "BUYER_LOCATION", "DOCUMENT_REFERENCES", "PAGE_REGIONS", "FREE_TEXT"] },
  report: REPORT };

/** No backend: the offer, the example, and a case whose report is refused until it is bought. */
async function api(page: Page) {
  let paid = false;
  await page.route("**/api/v1/**", route => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path === "/api/v1/offer") return route.fulfill({ json: OFFER });
    if (path === "/api/v1/sample-report") return route.fulfill({ json: SAMPLE });
    if (path === "/api/v1/intake-options") return route.fulfill({ json: { supported: ["US-NC"], consentPolicies: {} } });
    if (path === "/api/v1/cases/case") return route.fulfill({ json: { id: "case" } });
    if (path.endsWith("/dealer-offers/latest")) return route.fulfill({ json: { id: "o1", version: 1, status: "VERIFIED" } });
    if (path.endsWith("/purchases")) {
      expect(request.headers()["idempotency-key"]).toBeTruthy();
      paid = true;
      return route.fulfill({ status: 201, json: { purchaseId: "p1", status: "PAID", checkoutUrl: null, checkout: "DEVELOPMENT_NO_CHARGE" } });
    }
    if (path.endsWith("/reports/latest")) {
      return paid ? route.fulfill({ json: REPORT }) : route.fulfill({ status: 402, json: { code: "PURCHASE_REQUIRED", offerId: "offer-1" } });
    }
    if (path.endsWith("/negotiation")) return route.fulfill({ json: { language: { draft: "Review the fees", items: [] } } });
    if (path.endsWith("document-limits")) {
      return route.fulfill({ json: { acceptedMediaTypes: ["application/pdf"], maxFileBytes: 20971520, maxPages: 25, maxPixels: 40000000, minWidth: 1, minHeight: 1 } });
    }
    return route.fulfill({ status: 404, json: {} });
  });
}

async function expectNoHorizontalOverflow(page: Page) {
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
}

async function expectNoAxeViolations(page: Page) {
  const results = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"]).analyze();
  expect(results.violations.map(violation => ({ id: violation.id, nodes: violation.nodes.map(node => node.html) }))).toEqual([]);
}

test.beforeEach(async ({ page }) => { await api(page); });

test("the offer terms are stated on the intake screen at 360 px", async ({ page }) => {
  await page.goto("/");
  const terms = page.getByRole("region", { name: "What is free and what you pay for" });
  await expect(terms).toBeVisible();
  await expect(terms).toContainText("$49.00");
  await expect(terms).toContainText("Full refund on request within 14 days of payment.");
  await expect(terms).toContainText("Pilot state: North Carolina.");
  await expect(terms).toContainText("Private-party sales");
  await expect(terms).toContainText("No legal or tax conclusions.");
  await expect(terms).toContainText("Development checkout.");
  await expect(page.getByRole("heading", { name: "Start your Buying Case" })).toBeVisible();
  await expectNoHorizontalOverflow(page);
  await expectNoAxeViolations(page);
});

test("the example report opens before any case at 360 px", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("link", { name: "See an example report" }).click();
  await expect(page).toHaveURL(/\?page=example-report$/);
  const example = page.getByRole("article", { name: "See an example report" });
  await expect(example.getByRole("heading", { name: "Deal Analysis Report", exact: true })).toBeFocused();
  await expect(example.getByRole("note", { name: "About this example" })).toContainText("Built from a synthetic case");
  await expect(example.getByRole("note", { name: "About this example" })).toContainText("Redaction method: named categories of fields are removed; generated wording and amounts are kept (version sample-redaction.v2).");
  await expect(example.getByText(/Document source: removed from this example/)).toHaveCount(2);
  await expectNoHorizontalOverflow(page);
  await expectNoAxeViolations(page);
});

test("the payment point replaces the report error and a paid purchase opens the report at 360 px", async ({ page }) => {
  await page.goto("/#case=case&access=token");
  const point = page.getByRole("region", { name: "Open your Deal Analysis Report" });
  await expect(point).toBeVisible();
  await expect(point).toContainText("Development checkout.");
  await expect(page.getByText("Analysis could not finish.", { exact: false })).toHaveCount(0);
  await point.getByText("Price terms, refunds, and scope").click();
  await expectNoHorizontalOverflow(page);
  await expectNoAxeViolations(page);

  await point.getByRole("button", { name: "Pay $49.00 and open the report" }).click();
  await expect(page.getByRole("heading", { name: "Deal Analysis Report", exact: true })).toBeVisible();
  await expectNoHorizontalOverflow(page);
});

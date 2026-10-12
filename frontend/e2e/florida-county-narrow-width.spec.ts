import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";

test("synthetic Florida intake chooses the registration county and fits at 360 px", async ({ page }) => {
  await page.route("**/api/v1/**", route => route.fulfill({ status: 204, body: "" }));
  await page.route("**/api/v1/intake-options", route => route.fulfill({ json: {
    supported: ["US-FL"], consentPolicies: { serviceProcessing: "service-processing.v1" },
  } }));
  await page.route("**/api/v1/cases", async route => {
    const body = route.request().postDataJSON();
    if (body.buyerCounty) {
      expect(body.buyerCounty).toBe("Suwannee");
      return route.fulfill({ status: 201, json: { case: { id: "synthetic-florida" }, accessToken: "synthetic-token" } });
    }
    return route.fulfill({ status: 422, json: { detail: "Select the registration county.", field: "buyerCounty",
      reason: "POSTAL_CODE_CROSSES_COUNTY_LINE", candidateJurisdictions: ["Columbia", "Suwannee"] } });
  });
  await page.route("**/api/v1/cases/synthetic-florida/vehicle-evidence", route => route.fulfill({
    json: { identityConflicts: [], reliableForAnalysis: true },
  }));
  await page.goto("/");
  await page.getByLabel(/Your ZIP code/).fill("32024");
  await page.getByLabel(/Dealer ZIP code/).fill("32003");
  await page.getByLabel("VIN", { exact: true }).fill("1HGCM82633A004352");
  await page.getByRole("button", { name: "Start my case" }).click();
  const county = page.getByLabel(/Florida county where/);
  await county.selectOption("Suwannee");
  await expect(county).toHaveAccessibleDescription(/Suwannee County.*first \$5,000/);
  await expect(page.locator("html")).toHaveJSProperty("scrollWidth", 360);
  expect((await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"]).analyze()).violations).toEqual([]);
  await page.getByRole("button", { name: "Start my case" }).click();
  await expect(county).not.toBeVisible();
});

test("synthetic Florida tax evidence names the county and keeps component arithmetic within 360 px", async ({ page }) => {
  const report = { version: 1, asOf: "2026-10-12", status: "COMPLETE", analysis: {
    reviewLabel: "Review required", dealRating: "FAIR_NEGOTIATE", ratingBasis: "Synthetic fixture",
    nextAction: "Review the recorded tax inputs.", topReasons: [], marketAvailability: "AVAILABLE", cashAvailability: "AVAILABLE",
    policyVersions: { cash: "US-FL.cash.2026.v1:Broward" }, limitations: [],
    materialAmounts: [{ label: "/result/taxes", minorUnits: 215000, currency: "USD", precision: "ESTIMATED",
      source: "cashEconomics", pointer: "/result/taxes", calculation: { operands: [
        { operator: null, label: "Florida state sales tax: 35000.00 USD × 6%", minorUnits: null, value: "2100 USD" },
        { operator: "+", label: "Broward County surtax (first $5,000 after trade-in): 5000 USD × 1%", minorUnits: null, value: "50 USD" },
      ], notes: ["Florida state sales tax: Florida Statutes 2026 s.212.05(1)(a)1 (checked 2026-10-12).",
        "Broward County surtax (first $5,000 after trade-in): Florida DOR DR-15DSS Calendar Year 2026 R.11/25 (checked 2026-10-12)."] } }],
  }, evidence: { dealerOffer: { id: "synthetic-offer" } }, reproduction: {} };
  await page.route("**/api/v1/**", route => {
    const path = new URL(route.request().url()).pathname;
    const body = path.endsWith("dealer-offers/latest") ? { id: "synthetic-offer", version: 1, status: "VERIFIED" }
      : path.endsWith("reports/latest") ? report
      : path === "/api/v1/cases/synthetic-florida" ? { id: "synthetic-florida" }
      : path.endsWith("document-limits") ? { acceptedMediaTypes: ["application/pdf"], maxFileBytes: 20971520,
        maxPages: 25, maxPixels: 40000000, minWidth: 1, minHeight: 1 }
      : path.endsWith("negotiation") ? { language: { draft: "Synthetic fixture", items: [] } }
      : {};
    return route.fulfill({ json: body });
  });
  await page.goto("/#case=synthetic-florida&access=synthetic-token");
  await page.getByRole("heading", { name: "Amounts and sources", exact: true }).click();
  await page.getByRole("link", { name: "Source and calculation 1" }).click();
  await expect(page.getByText("Broward County surtax (first $5,000 after trade-in): 5000 USD × 1%", { exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
  expect((await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"]).analyze()).violations).toEqual([]);
});

import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";
import { syntheticTactic } from "../src/DeskTactics.fixture";

const agreedPrice = { offerVersion: 2, baselineVersion: 1, vin: "1HGCM82633A004352", sellingPriceMinor: 2500000,
  outTheDoorMinor: 2680000, acceptedAt: "2026-10-04T12:00:00Z" };
function fixture(state: string) {
  const base = { status: "AWAITING_PAPERWORK", source: null, agreedPrice, paperwork: null, findings: [], otherDifferences: [], unchangedTerms: 0,
    totalEffect: { basis: "UNAVAILABLE", currency: "USD", outTheDoorDeltaMinor: null, preTaxDeltaMinor: null, taxEffectMinor: null, addedChargesMinor: null },
    freshCase: null, dealReviewVersion: null };
  if (state === "OTHER_CAR") return { ...base, status: "DIFFERENT_VIN", source: "READ_FROM_PAPERWORK",
    findings: [{ group: "VEHICLE", term: "candidate:VEHICLE_VIN#1", label: "VEHICLE_VIN", kind: "CHANGED", agreed: { value: agreedPrice.vin, unit: "VIN" },
      desk: { value: "1M8GDM9AXKP042788", unit: "VIN" }, delta: null, unit: "VIN", severity: "BLOCKING", verified: false, evidence: null, buyerAction: null }],
    freshCase: { vin: "1M8GDM9AXKP042788", dealerPostalCode: "27604", purchaseMethod: "CASH" } };
  return { ...base, status: "DIFFERENCES", source: "READ_FROM_PAPERWORK",
    tacticFindings: [syntheticTactic],
    findings: [{ group: "ADDED", term: "line:service contract#1", label: "Vehicle service contract with extended powertrain and wear-item coverage", kind: "ADDED",
      agreed: null, desk: { amountMinor: 149500, currency: "USD" }, delta: null, unit: "USD_MINOR", severity: "MATERIAL", verified: false,
      evidence: { pageNumber: 2 }, buyerAction: "Ask the dealer to clarify and verify this term." },
    { group: "TOTAL", term: "candidate:OUT_THE_DOOR_TOTAL#1", label: "OUT_THE_DOOR_TOTAL", kind: "CHANGED", agreed: { value: "2680000", unit: "USD_MINOR" },
      desk: { value: "2829500", unit: "USD_MINOR" }, delta: 149500, unit: "USD_MINOR", severity: "MATERIAL", verified: false, evidence: { pageNumber: 2 }, buyerAction: null }],
    otherDifferences: [{ group: "OTHER", term: "candidate:DOCUMENT_NUMBER#1", label: "DOCUMENT_NUMBER", kind: "CHANGED", agreed: { value: "A-42", unit: "TEXT" },
      desk: { value: "A-57", unit: "TEXT" }, delta: null, unit: "TEXT", severity: "MATERIAL", verified: false, evidence: null, buyerAction: null }],
    totalEffect: { basis: "QUOTED", currency: "USD", outTheDoorDeltaMinor: 149500, preTaxDeltaMinor: null, taxEffectMinor: null, addedChargesMinor: 149500 } };
}

// The narrow and narrow-dark projects run each state in both themes.
for (const state of ["AWAITING", "DIFFERENCES", "OTHER_CAR"]) {
  test(`Desk Check ${state} works one-handed at 360 px`, async ({ page }) => {
    await page.route("**/api/v1/**", route => route.fulfill({ status: 204, body: "" }));
    await page.route("**/api/v1/intake-options", route => route.fulfill({ json: { supported: ["US-NC"], consentPolicies: {
      serviceProcessing: "service-processing.v1", humanReview: "human-review.v1", anonymizedResearch: "anonymized-research.v1",
    } } }));
    await page.route("**/api/v1/document-limits", route => route.fulfill({ json: { acceptedMediaTypes: ["application/pdf", "image/heic", "image/jpeg", "image/png"],
      maxFileBytes: 20971520, maxPages: 25, maxPixels: 40000000, minWidth: 2, minHeight: 2 } }));
    await page.route("**/api/v1/cases", route => route.fulfill({ status: 201, json: { case: { id: "case-1" }, accessToken: "secret" } }));
    await page.route("**/api/v1/cases/case-1/vehicle-evidence", route => route.fulfill({ json: { identityConflicts: [], reliableForAnalysis: true } }));
    await page.route("**/api/v1/cases/case-1/desk-check", route => route.fulfill({ json: fixture(state) }));
    await page.goto("/");
    await page.getByLabel(/your zip code/i).fill("27601");
    await page.getByLabel(/dealer zip code/i).fill("27604");
    await page.getByLabel(/^vin$/i).fill("1HGCM82633A004352");
    await page.getByRole("button", { name: /start my case/i }).click();
    const panel = page.locator(".desk-check");
    // Opening the agreed case brings the check into view; the camera control is the next tap.
    await expect(panel.getByRole("heading", { name: "Desk Check" })).toBeInViewport();
    await expect(panel.getByLabel("Photograph a page with your camera")).toHaveAttribute("capture", "environment");
    if (state === "DIFFERENCES") {
      await expect(panel.getByRole("list", { name: "Differences from your Agreed Price" }).getByRole("listitem").first())
        .toContainText("Added: Vehicle service contract");
      await panel.getByText("1 other change on the paperwork").click();
      await expect(panel.getByText("Question wording awaits operator review.")).toBeVisible();
      await panel.getByText("Calculation and evidence", { exact: true }).click();
    }
    if (state === "OTHER_CAR") {
      await expect(panel.getByRole("alert").first()).toContainText("Different car");
      await panel.getByRole("button", { name: "Start a fresh case for this car" }).click();
      await expect(panel.getByLabel(/^vin$/i)).toHaveValue("1M8GDM9AXKP042788");
    }
    // Let the theme cross-fade and entrance animation settle before measuring colours and layout.
    await page.waitForTimeout(600);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
    for (const button of await panel.locator(".desk-check-result button").all())
      expect((await button.boundingBox())!.height).toBeGreaterThanOrEqual(44);
    const results = await new AxeBuilder({ page }).include(".desk-check").withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"]).analyze();
    expect(results.violations.map(violation => ({ id: violation.id, nodes: violation.nodes.map(node => node.html) }))).toEqual([]);
  });
}

import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";
import { syntheticTactic, syntheticTradeTactics } from "../src/DeskTactics.fixture";

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
  const differences = { ...base, status: "DIFFERENCES", source: "READ_FROM_PAPERWORK",
    tacticFindings: [syntheticTactic],
    findings: [{ group: "ADDED", term: "line:service contract#1", label: "Vehicle service contract with extended powertrain and wear-item coverage", kind: "ADDED",
      agreed: null, desk: { amountMinor: 149500, currency: "USD" }, delta: null, unit: "USD_MINOR", severity: "MATERIAL", verified: false,
      evidence: { pageNumber: 2 }, buyerAction: "Ask the dealer to clarify and verify this term." },
    { group: "TOTAL", term: "candidate:OUT_THE_DOOR_TOTAL#1", label: "OUT_THE_DOOR_TOTAL", kind: "CHANGED", agreed: { value: "2680000", unit: "USD_MINOR" },
      desk: { value: "2829500", unit: "USD_MINOR" }, delta: 149500, unit: "USD_MINOR", severity: "MATERIAL", verified: false, evidence: { pageNumber: 2 }, buyerAction: null }],
    otherDifferences: [{ group: "OTHER", term: "candidate:DOCUMENT_NUMBER#1", label: "DOCUMENT_NUMBER", kind: "CHANGED", agreed: { value: "A-42", unit: "TEXT" },
      desk: { value: "A-57", unit: "TEXT" }, delta: null, unit: "TEXT", severity: "MATERIAL", verified: false, evidence: null, buyerAction: null }],
    totalEffect: { basis: "QUOTED", currency: "USD", outTheDoorDeltaMinor: 149500, preTaxDeltaMinor: null, taxEffectMinor: null, addedChargesMinor: 149500 } };
  if (state !== "TRADE" && state !== "TRADE_OFFSET") return differences;
  // Ticket 103, labelled synthetic: an $11,500 allowance against a $14,000 written offer and a $19,200 payoff.
  const shortfall = "The trade-in allowance of $11,500 is $2,500 below the written offer from Synthetic Coastal Vehicle Exchange and Appraisal Center dated 2026-10-09 for $14,000.";
  const tradeView = { ...differences, tacticFindings: [syntheticTradeTactics[0]], findings: [{ group: "TRADE", term: "trade:BELOW_WRITTEN_OFFER", label: "BELOW_WRITTEN_OFFER", kind: "TRADE_CHECK",
    agreed: null, desk: null, delta: 250000, unit: "USD_MINOR", severity: "WARNING", verified: false, evidence: { pageNumber: 2 },
    buyerAction: "I have a written offer of $14,000 for my trade. Will you match it?" }],
  trade: { status: "CHECKED", allowanceMinor: 1150000, paperworkPayoffMinor: 1920000,
    buyerPayoff: { state: "CURRENT", amountMinor: 1800000, goodThrough: "2026-10-10" }, payoffDifferenceMinor: 120000, equityMinor: -770000,
    equityBasis: "PAPERWORK_PAYOFF", negativeEquityFinancedMinor: 770000,
    negativeEquityCost: { status: "AVAILABLE", reason: null, aprPercent: 6.9, termMonths: 72, paymentIncreaseMinor: 13093,
      scheduledPaymentsIncreaseMinor: 942696, interestMinor: 172696 },
    bestOffer: { id: "o1", amountMinor: 1400000, source: "Synthetic Coastal Vehicle Exchange and Appraisal Center", offerDate: "2026-10-09",
      expiresOn: "2026-10-17", label: "the written offer" },
    shortfallToOfferMinor: 250000,
    taxCredit: { status: "AVAILABLE", reason: null, jurisdiction: "US-NC", policyVersion: "US-NC.cash.v1", creditMinor: 34500 }, offerGapAfterTaxMinor: 215500,
    valuation: { availability: "AVAILABLE", notice: "Synthetic trade-in range for development only.", source: "SYNTHETIC_TRADE_VALUATION", synthetic: true,
      retrievedAt: "2026-10-10T15:00:00Z", conditionAssumption: "GOOD", lowMinor: 1400000, typicalMinor: 1600000, highMinor: 1800000, position: "BELOW" },
    vinMatch: { status: "SAME", paperworkVin: "5J6RW2H52ML000002", recordedVin: "5J6RW2H52ML000002" },
    findings: [
      { code: "BELOW_WRITTEN_OFFER", severity: "WARNING", summary: shortfall, amountMinor: 250000,
        deskQuestion: "I have a written offer of $14,000 for my trade. Will you match it?", estimate: false },
      { code: "BELOW_VALUATION_RANGE", severity: "WARNING", summary: "The trade-in allowance of $11,500 is $2,500 below the low end of the trade-in range, $14,000 to $18,000 (an estimate from SYNTHETIC_TRADE_VALUATION).",
        amountMinor: 250000, deskQuestion: "The trade-in range I have starts at $14,000. How did you arrive at $11,500?", estimate: true },
      { code: "PAYOFF_DIFFERS", severity: "WARNING", summary: "This paperwork uses a payoff of $19,200, $1,200 more than your payoff of $18,000 valid through 2026-10-10.",
        amountMinor: 120000, deskQuestion: "My lender's payoff is $18,000 valid through 2026-10-10. Why does this use $19,200?", estimate: false },
      { code: "NEGATIVE_EQUITY_FINANCED", severity: "WARNING", summary: "The negative equity is added to the loan and has a scheduled payment effect.",
        amountMinor: 942696, deskQuestion: "Original trade question", estimate: false }] } };
  if (state === "TRADE_OFFSET") return { ...tradeView, tacticFindings: [syntheticTradeTactics[1]],
    findings: [{ ...tradeView.findings[0], term: "trade:ALLOWANCE_OFFSET_BY_PRICE", label: "ALLOWANCE_OFFSET_BY_PRICE", delta: 100000 }],
    trade: { ...tradeView.trade, allowanceMinor: 1800000, paperworkPayoffMinor: 1700000,
      buyerPayoff: { ...tradeView.trade.buyerPayoff, amountMinor: 1700000 }, payoffDifferenceMinor: 0, equityMinor: 100000,
      negativeEquityFinancedMinor: null, negativeEquityCost: null, shortfallToOfferMinor: 0,
      offerGapAfterTaxMinor: -454000, taxCredit: { ...tradeView.trade.taxCredit, creditMinor: 54000 },
      valuation: { ...tradeView.trade.valuation, position: "WITHIN" },
      findings: [{ code: "ALLOWANCE_OFFSET_BY_PRICE", severity: "WARNING", summary: "The allowance is $4,000 above the written offer, but the price is $5,000 higher than agreed, leaving you $1,000 behind.",
        amountMinor: 100000, deskQuestion: "Original trade question", estimate: false }] } };
  return tradeView;
}

// The narrow and narrow-dark projects run each state in both themes.
for (const state of ["AWAITING", "DIFFERENCES", "OTHER_CAR", "TRADE", "TRADE_OFFSET"]) {
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
    await page.getByLabel(/dealer city/i).fill("27604");
    await page.getByLabel("Dealer state").selectOption("NC");
    await page.getByLabel(/^vin$/i).fill("1HGCM82633A004352");
    await page.getByRole("button", { name: /start my case/i }).click();
    const panel = page.locator(".desk-check");
    // A compared paperwork opens on Final check (ticket 112); its button leads to each difference.
    if (state !== "OTHER_CAR") {
      await expect(page.getByRole("navigation", { name: "Case progress" }).getByRole("button", { name: /Final check/ }))
        .toHaveAttribute("aria-current", "step");
      await page.getByRole("button", { name: "See each difference" }).click();
    }
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
    if (state === "TRADE") {
      await expect(panel.getByRole("list", { name: "Differences from your Agreed Price" }).getByRole("listitem").first())
        .toContainText("$2,500 below the written offer");
      await expect(panel.getByRole("heading", { name: "Trade-in check" })).toBeVisible();
      await expect(panel.getByRole("list", { name: "Trade-in findings" }).getByText("Estimate", { exact: true })).toBeVisible();
    }
    if (state === "TRADE" || state === "TRADE_OFFSET") {
      const tactic = syntheticTradeTactics[state === "TRADE" ? 0 : 1];
      await expect(panel.getByText(tactic.label, { exact: true })).toHaveCount(1);
      await expect(panel.getByRole("region", { name: "Worksheet tactics" })).toHaveCount(0);
      await panel.getByText("Tactic evidence and policy", { exact: true }).click();
      await expect(panel.getByText("Question wording awaits operator review.")).toBeVisible();
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

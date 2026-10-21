import { AxeBuilder, expect, Page, test } from "./fixtures";

// Ticket 71: the report's secondary sections are one collapsible list below the leading section.
const report = { version: 1, asOf: "2026-10-05", status: "COMPLETE", analysis: {
  docFeeFindings: [{
    reason: "Doc fee: $899.00 on the paperwork. Texas has a $225 documentary fee safe harbor. Before charging more, a dealer must file a notification and supporting cost analysis with the OCCC. A higher fee alone does not establish a breach of this rule. Source: 7 TAC 84.205(b)-(d). Checked 2026-10-18; review by 2027-01-18.",
    buyerAction: "Has your dealership filed for a doc fee above $225 with the OCCC, including the supporting cost analysis?",
    uncertain: false, observedEvidence: { rule: { citation: "7 TAC 84.205(b)-(d); OCCC Bulletin B16-5 (July 2, 2024)",
      url: "https://occc.texas.gov/wp-content/uploads/2025/11/b16-5-mvsf-doc-fee-filing-instructions-7-2-24_0.pdf" } },
  }],
  // Ticket 142: the named add-ons sit beside the doc fee check in Amounts and sources.
  dealerAddOns: { policyVersion: "dealer-add-on-check.v1", catalogueVersion: "dealer-add-ons.2026-10.v1", financed: false,
    optionalTotalMinor: 179400, possibleTotalMinor: 49900, unrecognisedTotalMinor: 99500, unreadAmounts: 0,
    summary: "This paperwork lists 2 named add-ons and dealer charges, 1 possible add-on and 1 unrecognised charge. Usually optional items total $1,794. Possible add-ons total $499. Unrecognised charges total $995.",
    listingNote: null,
    lines: [
      { label: "Vehicle service contract with extended powertrain and wear-item coverage", amountMinor: 149500, status: "RECOGNISED",
        chargeIds: ["service-contract"], name: "service contract or extended warranty", family: "Products sold in the finance office",
        kind: "FINANCE_PRODUCT", what: "A contract that pays for some repairs after the factory warranty, sold for a set price and term.",
        kindWording: "Optional product; you can decline it or buy it elsewhere.", optional: true, confirmedByBuyer: false, verified: false,
        note: "This is recorded as a cash purchase, so its price is paid now, with no interest on it.",
        question: "Is the service contract optional? What is its price on its own, and can I decline it?" },
      { label: "VIN ETCH", amountMinor: 29900, status: "RECOGNISED", chargeIds: ["vin-etch"], name: "VIN etching", family: "Theft deterrent",
        kind: "OPTIONAL_ADD_ON", what: "The vehicle identification number etched into the windows to discourage theft.",
        kindWording: "Usually optional and negotiable.", optional: true, confirmedByBuyer: false, verified: false, note: null,
        question: "Are the windows already etched? If the etching is optional, will you take it off the price?" },
      { label: "Exterior Shine", amountMinor: 49900, status: "POSSIBLE", chargeIds: ["paint-protection"], name: "paint protection",
        family: "Pre-installed protection", kind: "OPTIONAL_ADD_ON", what: null, kindWording: null, optional: false, confirmedByBuyer: false,
        verified: false, note: "Possibly paint protection.", question: "Is the “Exterior Shine” paint protection? What does it cover, and is it optional?" },
      { label: "PlatinumPeaceOfMindDealerCertifiedProtectionPackage", amountMinor: 99500, status: "UNRECOGNISED", chargeIds: [], name: null,
        family: null, kind: null, what: null, kindWording: null, optional: false, confirmedByBuyer: false, verified: false,
        note: "Unrecognised charge — ask what it is.", question: "What is the “PlatinumPeaceOfMindDealerCertifiedProtectionPackage”, and is it optional?" },
    ] },
  marketEvidence: { status: "AVAILABLE", paragraphs: ["Market data retrieved: October 1, 2026."] },
  reviewLabel: "Review required", dealRating: "FAIR", ratingBasis: "Recorded evidence", nextAction: "Review the fees",
  topReasons: ["The documentation fee is above the recorded range.", "Market selling-price evidence is available; it is an estimate.",
    "The quoted total needs reconciliation with recorded charges."],
  marketAvailability: "AVAILABLE", cashAvailability: "AVAILABLE", financingAvailability: "UNAVAILABLE", financing: null,
  materialAmounts: [
    { label: "Documentation fee", minorUnits: 89900, currency: "USD", precision: "EXACT", source: "cashEconomics", pointer: "/result/lineItems/0/amount",
      documentSource: null, sourceNote: "Read from the recorded line item." },
    { label: "/result/outTheDoorTotal", minorUnits: 2850000, currency: "USD", precision: "ESTIMATED", source: "cashEconomics", pointer: "/result/outTheDoorTotal",
      documentSource: null, sourceNote: "Calculated from the inputs below; no single document line holds this amount.",
      calculation: { operands: [{ operator: null, label: "Vehicle subtotal", minorUnits: 2610000, value: null },
        { operator: "+", label: "Taxes", minorUnits: 240000, value: null }], notes: [] } },
  ], policyVersions: { review: "core-review.v1" }, limitations: ["Financial consistency only."],
}, evidence: {}, reproduction: {} };
const SECTIONS = ["Amounts and sources", "Dealer financing", "Market evidence", "Evidence and calculations", "Scope and policies",
  "Optional outcome feedback", "Save a copy"];

/** `reportGate` holds the saved report back until the test releases it, so a fragment can be set first. */
async function api(page: Page, reportGate?: Promise<void>) {
  await page.route("**/api/v1/**", async route => {
    const path = new URL(route.request().url()).pathname;
    if (path === "/api/v1/cases/current") return route.fulfill({ json: { id: "current" } });
    if (path.endsWith("reports/latest") && reportGate) await reportGate;
    const body = path.endsWith("negotiation") ? { language: { draft: "Review the fees", items: [] } }
      : path.endsWith("dealer-offers/latest") ? { version: 1, status: "VERIFIED" }
      : path.endsWith("analyses") ? { operation: { id: "op", stage: "QUEUED" } }
      : path.endsWith("operations/op") ? { stage: "COMPLETE" }
      : path.endsWith("reports/latest") ? report
      : path.endsWith("feedback") ? { experiment: null }
      : path.endsWith("document-limits") ? { acceptedMediaTypes: ["application/pdf"], maxFileBytes: 20971520, maxPages: 25, maxPixels: 40000000, minWidth: 1, minHeight: 1 }
      : path.endsWith("intake-options") ? { supported: ["US-NC"], consentPolicies: {} }
      : {};
    await route.fulfill({ json: body });
  });
}

async function openReport(page: Page) {
  await page.goto("/#case=current&access=private-secret");
  await expect(page.getByRole("heading", { name: "Deal Analysis Report", exact: true })).toBeFocused();
  return page.getByRole("list", { name: "Report details" });
}

const section = (page: Page, name: string) => page.locator("details.report-section", { has: page.getByRole("heading", { name, exact: true, level: 3 }) });

async function expectInViewport(page: Page, id: string) {
  const box = await page.locator(`[id="${id}"]`).boundingBox();
  expect(box).not.toBeNull();
  expect(box!.y).toBeGreaterThanOrEqual(0);
  expect(box!.y).toBeLessThan(780);
}

async function expectNoHorizontalOverflow(page: Page) {
  const widest = await page.evaluate(() => Array.from(document.querySelectorAll<HTMLElement>("body *"))
    .filter(element => element.getBoundingClientRect().right > document.documentElement.clientWidth + 1)
    .map(element => `${element.tagName.toLowerCase()}.${element.className}`).slice(0, 5));
  expect(widest).toEqual([]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
}

async function expectNoAxeViolations(page: Page) {
  const results = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"]).analyze();
  expect(results.violations.map(violation => ({ id: violation.id, nodes: violation.nodes.map(node => node.html) }))).toEqual([]);
}

test("the secondary sections start collapsed below an always-open lead, open from the keyboard and fit 360 px", async ({ page }) => {
  await api(page);
  const list = await openReport(page);
  await expect(page.getByRole("region", { name: "Total, findings and next action" })).toBeVisible();
  await expect(page.getByRole("region", { name: "Questions to ask the dealer" })).toBeVisible();
  await expect(list.locator(":scope > li > details > summary")).toHaveText(SECTIONS);
  for (const name of SECTIONS) await expect(section(page, name)).not.toHaveAttribute("open");
  await expect(page.getByText("Market data retrieved: October 1, 2026.")).toBeHidden();
  await expectNoHorizontalOverflow(page);
  await expectNoAxeViolations(page);

  const summary = section(page, "Market evidence").locator(":scope > summary");
  await summary.focus();
  await page.keyboard.press("Enter");
  await expect(section(page, "Market evidence")).toHaveAttribute("open");
  await expect(page.getByRole("region", { name: "Market evidence", exact: true })).toContainText("Market data retrieved: October 1, 2026.");
  await page.keyboard.press("Space");
  await expect(section(page, "Market evidence")).not.toHaveAttribute("open");

  for (const name of SECTIONS) await section(page, name).locator(":scope > summary").click();
  for (const name of SECTIONS) await expect(section(page, name)).toHaveAttribute("open");
  await expect(section(page, "Amounts and sources").getByText("Unrecognised charge — ask what it is.")).toBeVisible();
  await expectNoHorizontalOverflow(page);
  await expectNoAxeViolations(page);
});

test("links from the total, a finding and the section menu open their section, focus the target and survive the back button", async ({ page }) => {
  await api(page);
  await openReport(page);
  await page.getByRole("link", { name: "$28,500.00", exact: true }).click();
  await expect(section(page, "Evidence and calculations")).toHaveAttribute("open");
  await expect(page.locator("#amount-evidence-1")).toBeFocused();
  await expectInViewport(page, "amount-evidence-1");

  await page.getByRole("navigation", { name: "Report sections" }).getByRole("link", { name: "Amounts" }).click();
  await expect(section(page, "Amounts and sources")).toHaveAttribute("open");
  await expect(page.locator("#core-amounts")).toBeFocused();
  await section(page, "Evidence and calculations").locator(":scope > summary").click();
  await expect(section(page, "Evidence and calculations")).not.toHaveAttribute("open");
  await page.goBack();
  await expect(section(page, "Evidence and calculations")).toHaveAttribute("open");
  await expect(page.locator("#amount-evidence-1")).toBeFocused();
  await expectInViewport(page, "amount-evidence-1");

  // A repeat click on the current fragment still reopens the section.
  await section(page, "Evidence and calculations").locator(":scope > summary").click();
  await page.getByRole("link", { name: "Frozen evidence" }).first().click();
  await expect(section(page, "Evidence and calculations")).toHaveAttribute("open");
  await expect(page.locator("#core-evidence")).toBeFocused();
  await expectNoHorizontalOverflow(page);
});

test("a report that opens at a fragment opens only that section", async ({ page }) => {
  let release!: () => void;
  await api(page, new Promise<void>(resolve => { release = resolve; }));
  await page.goto("/#case=current&access=private-secret");
  await expect.poll(() => page.evaluate(() => window.location.hash)).toBe("");
  await page.evaluate(() => window.history.replaceState(null, "", "#amount-evidence-0"));
  release();
  await expect(section(page, "Evidence and calculations")).toHaveAttribute("open");
  await expect(page.locator("#amount-evidence-0")).toBeFocused();
  await expectInViewport(page, "amount-evidence-0");
  for (const name of SECTIONS.filter(name => name !== "Evidence and calculations")) await expect(section(page, name)).not.toHaveAttribute("open");
});

test("printing expands every section in report order and restores the Buyer's choices", async ({ page }) => {
  await api(page);
  await openReport(page);
  await section(page, "Market evidence").locator(":scope > summary").click();
  await page.emulateMedia({ media: "print" });
  await page.evaluate(() => window.dispatchEvent(new Event("beforeprint")));
  const ids = await page.locator("details.report-section").evaluateAll(sections => sections.map(details => [details.id, (details as HTMLDetailsElement).open]));
  expect(ids).toEqual([["core-amounts", true], ["core-financing", true], ["core-market", true], ["core-evidence", true], ["core-scope", true],
    ["core-feedback", true], ["core-downloads", true]]);
  await expect(page.locator("#amount-evidence-1")).toBeVisible();
  await page.evaluate(() => window.dispatchEvent(new Event("afterprint")));
  await page.emulateMedia({ media: "screen" });
  const open = await page.locator("details.report-section[open]").evaluateAll(sections => sections.map(details => details.id));
  expect(open).toEqual(["core-market"]);
});

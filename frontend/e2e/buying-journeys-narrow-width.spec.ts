import { AxeBuilder, expect, Page, test } from "./fixtures";

const ACCOUNT = {
  contact: { email: "buyer@example.com", firstName: null, lastName: null, phone: null },
  createdAt: "2026-10-03T00:00:00Z", sessionExpiresAt: "2026-10-17T00:00:00Z", cases: [],
};
const FEATURES = ["All-wheel drive", "Third-row seating", "Apple CarPlay", "Android Auto", "Adaptive cruise control",
  "Blind-spot monitoring", "Backup camera", "Heated seats", "Ventilated seats", "Leather seats", "Sunroof or moonroof",
  "Navigation", "Remote start", "Tow package", "Hybrid powertrain", "Electric powertrain"];
const slug = (value: string) => value.toLowerCase().replace(/[^a-z0-9]+/g, "-");
const OPTIONS = {
  reference: {
    version: "vehicle-reference-2026-10-v1",
    makes: [{ id: "chevrolet", label: "Chevrolet", models: [{ id: "chevrolet-silverado-1500", label: "Silverado 1500",
      trims: ["WT", "Custom", "LT", "RST", "LTZ", "High Country"].map(label => ({ id: `chevrolet-silverado-1500-${slug(label)}`, label })) }] }],
    features: FEATURES.map(label => ({ id: slug(label), label })),
  },
  bounds: { earliestYear: 1990, latestYear: 2027, maxMileage: 300000, minPriceCeiling: 1000, maxPriceCeiling: 500000,
    minRadiusMiles: 5, maxRadiusMiles: 500, maxChoices: 20 },
};
const brief = (version: number, priceCeilingMinor: number) => ({
  journeyId: "j1", version, referenceListVersion: "vehicle-reference-2026-10-v1", yearFrom: 2019, yearTo: 2025,
  make: { id: "chevrolet", label: "Chevrolet" }, model: { id: "chevrolet-silverado-1500", label: "Silverado 1500" },
  trims: [{ id: "chevrolet-silverado-1500-high-country", label: "High Country" }], maxMileage: 120000, priceCeilingMinor,
  currency: "USD", postalCode: "27048", jurisdiction: "US-NC", radiusMiles: 250,
  mustHaveFeatures: FEATURES.slice(0, 6).map(label => ({ id: slug(label), label })),
  niceToHaveFeatures: FEATURES.slice(6, 12).map(label => ({ id: slug(label), label })), createdAt: "2026-10-03T12:00:00Z",
});
const SOURCE = { source: "NHTSA vPIC", decoderVersion: "nhtsa-vpic-decodevinvalues-adapter-v1" };
const DECODED = { referenceId: "r1", vin: "1GCUYHED0KZ000003", outcome: "DECODED", ...SOURCE, decodedAt: "2026-10-04T12:00:00Z",
  attributes: [["MODEL_YEAR", "Model year", "2019"], ["MAKE", "Make", "Chevrolet"], ["MODEL", "Model", "Silverado 1500"],
    ["TRIM", "Trim", "High Country"], ["BODY", "Body", "Pickup"], ["DRIVETRAIN", "Drivetrain", "Four-wheel drive"],
    ["ENGINE", "Engine", "6.2 L 8-cylinder gasoline"], ["TRANSMISSION", "Transmission", "10-speed automatic"]]
    .map(([attribute, label, value]) => ({ attribute, label, value, relevant: attribute === "TRIM", ...SOURCE })) };
const UNDECODABLE = { referenceId: "r2", vin: "3VW000003AA000006", outcome: "UNDECODABLE", ...SOURCE,
  decodedAt: "2026-10-04T12:00:00Z", attributes: [] };
const JOURNEY = { referenceVehicles: [DECODED, UNDECODABLE], id: "j1", state: "SHOPPING", createdAt: "2026-10-03T12:00:00Z", updatedAt: "2026-10-03T13:00:00Z",
  brief: brief(2, 5_500_000), versions: [{ version: 1, createdAt: "2026-10-03T12:00:00Z" }, { version: 2, createdAt: "2026-10-03T13:00:00Z" }] };
const SNAPSHOT = (staleness: string, observedAt: string | null) => ({ source: "SYNTHETIC_INVENTORY",
  retrievedAt: "2026-10-04T12:00:00Z", observedAt, staleness, synthetic: true });
const SILVERADO = { title: "2025 Chevrolet Silverado 1500 High Country", year: 2025, make: "Chevrolet", model: "Silverado 1500",
  trim: "High Country", distanceMiles: 248, currency: "USD",
  matched: [{ kind: "MUST_HAVE", text: "Trim High Country is one of your trims." },
    { kind: "MUST_HAVE", text: "Advertised price $54,999.99 is within your $55,000 ceiling." },
    { kind: "REFERENCE", text: "Matches your reference trim (High Country)." }],
  missed: [{ kind: "NICE_TO_HAVE", text: "Does not list ventilated seats, a nice-to-have." }] };
const CANDIDATES = { matcherVersion: "candidate-matching.v1", briefVersion: 2, referenceListVersion: "vehicle-reference-2026-10-v1",
  availability: "AVAILABLE", notice: "Synthetic inventory for development; these vehicles are not for sale.",
  provider: { name: "SYNTHETIC_INVENTORY", version: "deterministic-inventory.v1", licence: "SYNTHETIC_DISPLAY_AND_CACHE_PERMITTED",
    synthetic: true },
  retrievedAt: "2026-10-04T12:00:00Z",
  ordering: "Listings that state a fact missing a must-have are left out. The rest are ordered by: fewest must-haves the listing does not state, then most nice-to-have features listed, then most reference details matched.",
  candidates: [
    { ...SILVERADO, listingId: "match", vin: "1GCUYHED0KZ000003", mileage: 119999, advertisedPriceMinor: 5_499_999, unconfirmed: [],
      snapshot: SNAPSHOT("CURRENT", "2026-10-04T11:00:00Z") },
    { ...SILVERADO, listingId: "stale", vin: null, mileage: null, advertisedPriceMinor: null,
      unconfirmed: [{ kind: "REFERENCE", text: "The listing does not state its drivetrain, so your reference drivetrain (Four-wheel drive) is unconfirmed." }],
      snapshot: SNAPSHOT("STALE", "2026-09-26T12:00:00Z") }],
  excluded: [{ listingId: "near-miss", vin: null, title: "2025 Chevrolet Silverado 1500 High Country", snapshot: SNAPSHOT("CURRENT", null),
    missed: [{ kind: "MUST_HAVE", text: "121,000 miles is over your 120,000-mile maximum." }] }] };

/** The layout gate needs no real service, only the account and journey shapes the panels render from. */
async function stubApi(page: Page) {
  await page.route("**/api/v1/**", route => route.fulfill({ status: 204, body: "" }));
  await page.route("**/api/v1/intake-options", route => route.fulfill({ json: { supported: ["US-NC"], consentPolicies: {} } }));
  await page.route("**/api/v1/account/sign-in/verify", route =>
    route.fulfill({ headers: { "Set-Cookie": "account_session=session-1; HttpOnly; Secure; SameSite=Strict; Path=/api/v1; Max-Age=1209600" },
      json: { expiresAt: "2026-10-17T00:00:00Z" } }));
  await page.route("**/api/v1/account", route => route.request().headers().cookie?.includes("account_session=session-1")
    ? route.fulfill({ json: ACCOUNT }) : route.fulfill({ status: 401, json: { code: "SESSION_ENDED" } }));
  await page.route("**/api/v1/account/notifications", route => route.fulfill({ json: [] }));
  await page.route("**/api/v1/journeys/brief-options", route => route.fulfill({ json: OPTIONS }));
  await page.route("**/api/v1/journeys", route => route.request().method() === "POST"
    ? route.fulfill({ status: 422, json: { code: "LOCATION_NOT_SERVED", detail: "Some Shopping Brief fields need a correction.", errors: [
      { field: "yearFrom", message: "Enter the earliest model year as a year from 1990 to 2027." },
      { field: "priceCeiling", message: "Enter a price ceiling in whole dollars from $1,000 to $500,000." },
      { field: "jurisdiction", message: "That ZIP code crosses a state line, so the state cannot be determined from it. Choose your state.",
        reason: "POSTAL_CODE_CROSSES_STATE_LINE", candidateJurisdictions: ["US-NC", "US-VA"] }] } })
    : route.fulfill({ json: [{ id: "j1", state: "SHOPPING", createdAt: "2026-10-03T12:00:00Z", updatedAt: "", currentVersion: 2,
      yearFrom: 2019, yearTo: 2025, make: "Chevrolet", model: "Silverado 1500" }] }));
  await page.route("**/api/v1/journeys/j1", route => route.fulfill({ json: JOURNEY }));
  await page.route("**/api/v1/journeys/j1/briefs/1", route => route.fulfill({ json: brief(1, 6_000_000) }));
  await page.route("**/api/v1/journeys/j1/briefs/1/reference-vehicles", route => route.fulfill({ json: [DECODED] }));
  await page.route("**/api/v1/journeys/j1/candidates", route => route.fulfill({ json: CANDIDATES }));
  await page.route("**/api/v1/journeys/j1/reference-vehicles", route => route.fulfill({ status: 400, json: { code: "VIN_INVALID",
    detail: "That VIN was not decoded because it is not a valid VIN.",
    errors: [{ field: "vin", message: "This VIN fails the VIN checksum. Enter all 17 characters exactly as shown on the car or its paperwork; VINs never use the letters I, O or Q." }] } }));
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
    .include("section.journeys")
    .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
    .analyze();
  expect(results.violations).toEqual([]);
}

test("the journey list, the brief form with field errors, and a versioned journey fit 360 px", async ({ page }) => {
  await stubApi(page);
  await page.goto("/#sign-in=link-secret");
  await page.getByRole("button", { name: "Finish signing in" }).click();
  const panel = page.getByRole("region", { name: "Buying Journeys" });
  await expect(panel.getByRole("list", { name: "Your Buying Journeys" })).toContainText("Brief version 2");
  await expectNoOverflowOrViolations(page);

  await panel.getByRole("button", { name: "Start a Buying Journey" }).click();
  await panel.getByLabel("Find a make or model (optional)").fill("silverado");
  await expect(panel.getByRole("button", { name: "Choose Chevrolet Silverado 1500" })).toBeVisible();
  await panel.getByRole("button", { name: "Choose Chevrolet Silverado 1500" }).click();
  await expect(panel.getByRole("group", { name: /Trims/ })).toBeVisible();
  await panel.getByRole("button", { name: "Save brief and start journey" }).click();
  await expect(panel.getByLabel("Your state")).toBeVisible();
  await expect(panel.getByLabel("Earliest model year")).toHaveAttribute("aria-invalid", "true");
  await expectNoOverflowOrViolations(page);

  await panel.getByRole("button", { name: "Cancel" }).click();
  await panel.getByRole("button", { name: "Open 2019–2025 Chevrolet Silverado 1500" }).click();
  await expect(panel.getByText("$55,000")).toBeVisible();
  await panel.getByLabel("Brief version").selectOption("1");
  await expect(panel.getByText("$60,000")).toBeVisible();
  await expectNoOverflowOrViolations(page);
});

test("Reference Vehicles with decoded details, an unavailable decode, a checksum error and a read-only version fit 360 px", async ({ page }) => {
  await stubApi(page);
  await page.goto("/#sign-in=link-secret");
  await page.getByRole("button", { name: "Finish signing in" }).click();
  const panel = page.getByRole("region", { name: "Buying Journeys" });
  await panel.getByRole("button", { name: "Open 2019–2025 Chevrolet Silverado 1500" }).click();
  const references = panel.getByRole("region", { name: "Reference Vehicles" });
  await expect(references.getByRole("article", { name: "Reference Vehicle 1: 2019 Chevrolet Silverado 1500 High Country" })).toBeVisible();
  await expect(references.getByText(/Decoded details unavailable/)).toBeVisible();
  await expectNoOverflowOrViolations(page);

  await references.getByLabel("VIN of a car you like").fill("1GCUYHED1KZ000003");
  await references.getByRole("button", { name: "Decode and add VIN" }).click();
  await expect(references.getByLabel("VIN of a car you like")).toHaveAttribute("aria-invalid", "true");
  await expectNoOverflowOrViolations(page);

  await panel.getByLabel("Brief version").selectOption("1");
  await expect(references.getByLabel("Decoded details of Reference Vehicle 1")).toContainText("High Country · Matters");
  await expectNoOverflowOrViolations(page);
});

test("Candidate Vehicles with reasons, a stale listing and left-out listings fit 360 px", async ({ page }) => {
  await stubApi(page);
  await page.goto("/#sign-in=link-secret");
  await page.getByRole("button", { name: "Finish signing in" }).click();
  const panel = page.getByRole("region", { name: "Buying Journeys" });
  await panel.getByRole("button", { name: "Open 2019–2025 Chevrolet Silverado 1500" }).click();
  const candidates = panel.getByRole("region", { name: "Candidate Vehicles" });
  await expect(candidates.getByRole("button", { name: "Search inventory for candidates" })).toBeVisible();
  await expectNoOverflowOrViolations(page);

  await candidates.getByRole("button", { name: "Search inventory for candidates" }).click();
  await expect(candidates.getByRole("article", { name: "Candidate 1: 2025 Chevrolet Silverado 1500 High Country" })).toBeVisible();
  await expect(candidates.getByText(/Stale listing/)).toBeVisible();
  await candidates.getByText("1 listing was left out for missing a must-have").click();
  await expect(candidates.getByText("121,000 miles is over your 120,000-mile maximum.")).toBeVisible();
  await expectNoOverflowOrViolations(page);
});

// Ticket 56: saved candidate controls and confirmation at the narrow-width floor.
test("populated shortlist and case confirmation fit 360 px", async ({ page }) => {
  await stubApi(page);
  const marketCheck = { status: "AVAILABLE", confidence: "MEDIUM", range: { lowMinor: 5000000, highMinor: 5600000, currency: "USD" },
    provider: { retrievedAt: "2026-10-04T12:00:00Z" }, vintage: { oldestObservationAt: "2026-10-01T12:00:00Z", newestObservationAt: "2026-10-03T12:00:00Z" },
    limitation: "A small sample supports a broad estimated range." };
  await page.route("**/api/v1/journeys/j1/shortlist", route => route.fulfill({ json: [{ candidateId: "c1", caseId: null,
    candidate: CANDIDATES.candidates[0], marketCheck }] }));
  await page.goto("/#sign-in=link-secret");
  await page.getByRole("button", { name: "Finish signing in" }).click();
  await page.getByRole("button", { name: "Open 2019–2025 Chevrolet Silverado 1500" }).click();
  const shortlist = page.getByRole("region", { name: "Your shortlist" });
  await expect(shortlist.getByText(/Confidence: medium/)).toBeVisible();
  await expectNoOverflowOrViolations(page);
  await shortlist.getByRole("button", { name: "Open a Buying Case" }).click();
  await expect(shortlist.getByLabel("Dealer city")).toBeVisible();
  await expect(shortlist.getByLabel("Dealer state")).toBeVisible();
  await expectNoOverflowOrViolations(page);
});
// End ticket 56.

// Ticket 62: comparison and evidence in both theme projects.
test("verified quote comparison, missing figures and expanded evidence fit 360 px", async ({ page }) => {
  await stubApi(page);
  await page.route("**/api/v1/journeys/j1/quote-comparison", route => route.fulfill({ json: {
    otdComparable: false, cars: [{ caseId: "c1", candidateId: "v1", title: "2025 Chevrolet Silverado 1500 High Country",
      state: "Verified quote", offerVersion: 2, missing: [], noneListed: ["Add-ons", "Dealer fees"], reportVersion: 3,
      marketPosition: "Selling price within the stored market range.", amounts: [{ label: "Selling price", minorUnits: 5100000,
        currency: "USD", evidence: { sourceNote: null, calculation: null, documentSource: { documentId: "doc", extractionId: "ex",
          offerVersion: 2, wording: "Vehicle selling price", pageNumber: 2, region: { x: .1, y: .3, width: .5, height: .1 } } } }] },
      { caseId: "c2", candidateId: "v2", title: "2024 Chevrolet Silverado 1500 High Country", state: "Verified quote",
        offerVersion: 2, amounts: [], missing: ["Add-ons", "Figures do not add up to the quoted OTD by 125.00 USD"],
        reportVersion: null, marketPosition: "Market position unavailable." }],
  } }));
  await page.goto("/#sign-in=link-secret");
  await page.getByRole("button", { name: "Finish signing in" }).click();
  await page.getByRole("button", { name: "Open 2019–2025 Chevrolet Silverado 1500" }).click();
  const comparison = page.getByRole("region", { name: "Compare verified quotes" });
  await expect(comparison.getByText(/OTD totals cannot be compared/)).toBeVisible();
  await expect(comparison.getByText(/Figures do not add up to the quoted OTD by 125.00 USD/)).toBeVisible();
  await expect(comparison.getByText("None listed")).toHaveCount(2);
  await expectNoOverflowOrViolations(page);
  await comparison.getByText("Selling price evidence").click();
  await expect(comparison.getByText(/Read from page 2/)).toBeVisible();
  await expectNoOverflowOrViolations(page);
  await page.route("**/api/v1/journeys/j1/quote-comparison", route => route.fulfill({ json: {
    otdComparable: true, cars: [{ caseId: "c1", candidateId: "v1", title: "2025 Chevrolet Silverado 1500 High Country",
      state: "Verified quote", offerVersion: 2, missing: [], noneListed: ["Add-ons", "Dealer fees"], reportVersion: 3,
      marketPosition: "Selling price within the stored market range.", amounts: [{ label: "Out-the-door total", minorUnits: 5300000,
        currency: "USD", evidence: { sourceNote: "Verified quoted OTD.", documentSource: null, calculation: null } }] }],
  } }));
  await comparison.getByRole("button", { name: "Refresh quote comparison" }).click();
  await expect(comparison.getByText(/Every verified quoted OTD reconciles/)).toBeVisible();
  await comparison.getByLabel("Order quotes by").selectOption("otd");
  await expect(comparison.getByText("None listed")).toHaveCount(2);
  await expectNoOverflowOrViolations(page);
});
// End ticket 62.

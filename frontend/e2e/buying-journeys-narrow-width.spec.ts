import AxeBuilder from "@axe-core/playwright";
import { expect, Page, test } from "@playwright/test";

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

/** The layout gate needs no real service, only the account and journey shapes the panels render from. */
async function stubApi(page: Page) {
  await page.route("**/api/v1/**", route => route.fulfill({ status: 204, body: "" }));
  await page.route("**/api/v1/intake-options", route => route.fulfill({ json: { supported: ["US-NC"], consentPolicies: {} } }));
  await page.route("**/api/v1/account/sign-in/verify", route =>
    route.fulfill({ json: { sessionToken: "session-1", expiresAt: "2026-10-17T00:00:00Z" } }));
  await page.route("**/api/v1/account", route => route.fulfill({ json: ACCOUNT }));
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

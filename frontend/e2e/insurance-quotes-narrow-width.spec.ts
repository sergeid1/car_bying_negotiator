import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";
import { insuranceView } from "../src/InsuranceQuotes.fixture";

// Ticket 126: every step of the insurance panel, one-handed at 360 px, in both themes.
async function openCase(page: Page, view: unknown, refusal?: unknown) {
  await page.route("**/api/v1/**", route => route.fulfill({ status: 204, body: "" }));
  await page.route("**/api/v1/intake-options", route => route.fulfill({ json: { supported: ["US-NC"], consentPolicies: {
    serviceProcessing: "service-processing.v1", humanReview: "human-review.v1", anonymizedResearch: "anonymized-research.v1",
  } } }));
  await page.route("**/api/v1/cases", route => route.fulfill({ status: 201, json: { case: { id: "case-1" }, accessToken: "secret" } }));
  await page.route("**/api/v1/cases/case-1/vehicle-evidence", route => route.fulfill({ json: { identityConflicts: [], reliableForAnalysis: true } }));
  await page.route("**/api/v1/cases/case-1/insurance", route => route.request().method() === "PUT" && refusal
    ? route.fulfill({ status: 400, json: refusal }) : route.fulfill({ json: view }));
  await page.goto("/");
  await page.getByLabel(/your zip code/i).fill("27601");
  await page.getByLabel(/dealer city/i).fill("27604");
  await page.getByLabel("Dealer state").selectOption("NC");
  await page.getByLabel(/^vin$/i).fill("1HGCM82633A004352");
  await page.getByRole("button", { name: /start my case/i }).click();
  const panel = page.locator(".insurance-quotes");
  await expect(panel.getByRole("heading", { name: "Car insurance quotes" })).toBeVisible();
  return panel;
}

async function expectUsableAtNarrowWidth(page: Page, panel: ReturnType<Page["locator"]>) {
  // Let the theme cross-fade and entrance animation settle before measuring colours and layout.
  await page.waitForTimeout(600);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
  for (const control of await panel.locator("button, a.insurance-continue, input:not([type=radio]):not([type=checkbox]), select, label:has(> input[type=radio])").all())
    if (await control.isVisible()) expect((await control.boundingBox())!.height).toBeGreaterThanOrEqual(44);
  const results = await new AxeBuilder({ page }).include(".insurance-quotes").withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"]).analyze();
  expect(results.violations.map(violation => ({ id: violation.id, nodes: violation.nodes.map(node => node.html) }))).toEqual([]);
}

test("starting an insurance application from a case works one-handed at 360 px", async ({ page }) => {
  const panel = await openCase(page, insuranceView("EMPTY"));
  await expect(panel.getByText(/getting quotes does not buy a policy/)).toBeVisible();
  await expectUsableAtNarrowWidth(page, panel);
});

test("the insurance application with field errors and a different current coverage works one-handed at 360 px", async ({ page }) => {
  const panel = await openCase(page, insuranceView("EMPTY"), { detail: "Check the highlighted insurance details.", errors: [
    { field: "drivers[0].ageYears", message: "Enter the driver's age, from 15 to 120." },
    { field: "garaging.zip", message: "Enter the five-digit ZIP code where the car is kept overnight." },
    { field: "vehicle.usage", message: "Choose how the car is mostly driven." }] });
  await panel.getByRole("button", { name: "Start an insurance application" }).click();
  await expect(panel.getByLabel("Make")).toHaveValue("Honda");
  await panel.getByRole("checkbox", { name: "Collision" }).check();
  await panel.getByRole("button", { name: "Add another driver" }).click();
  await panel.getByRole("checkbox", { name: /I have a car insurance policy now/ }).check();
  await panel.getByRole("radio", { name: "No, it's different" }).check();
  await panel.getByRole("button", { name: "Save application" }).click();
  await expect(panel.getByText("Enter the driver's age, from 15 to 120.")).toBeVisible();
  await expectUsableAtNarrowWidth(page, panel);
});

test("the partner's consent works one-handed at 360 px", async ({ page }) => {
  const panel = await openCase(page, insuranceView("APPLIED"));
  await expect(panel.getByText("Gulfside Mutual Assurance of Northeast Florida, $910 for 6 months")).toBeVisible();
  await panel.getByRole("button", { name: "Get quotes" }).click();
  await expect(panel.getByText(/It goes to no one else/)).toBeVisible();
  await expect(panel.getByRole("button", { name: "Send and get quotes" })).toBeDisabled();
  await panel.getByRole("checkbox", { name: /I agree to send my application/ }).check();
  await expectUsableAtNarrowWidth(page, panel);
});

test("compared quotes, different coverage, declines, expired quotes and a quote of the Buyer's own work one-handed at 360 px", async ({ page }) => {
  const panel = await openCase(page, insuranceView("QUOTED"));
  await expect(panel.getByRole("list", { name: "Quotes for the coverage you chose" }).getByRole("listitem").first())
    .toContainText("Panhandle Farmers Insurance Exchange of the Gulf Coast");
  await expect(panel.getByText(/Companies shown here may pay otdog/)).toBeVisible();
  await expect(panel.getByRole("list", { name: "Quotes for different coverage" })).toContainText("Collision deductible: $1,000 here, $500 chosen.");
  await panel.getByRole("button", { name: "Add a quote I already have" }).click();
  await panel.getByRole("radio", { name: "No, different coverage" }).check();
  await expect(panel.getByLabel("Good until (optional)")).toBeVisible();
  await expectUsableAtNarrowWidth(page, panel);
});

import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";
import { conflictingListing, readCapture, savedListing } from "../src/ListingCapture.fixture";

// A 1 x 1 white PNG stands in for the normalized page of the Buyer's copy of the ad.
const PAGE_IMAGE = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4//8/AAX+Av4N70a4AAAAAElFTkSuQmCC", "base64");

async function openCase(page: Page, view: unknown) {
  await page.route("**/api/v1/**", route => route.fulfill({ status: 204, body: "" }));
  await page.route("**/api/v1/intake-options", route => route.fulfill({ json: { supported: ["US-NC"], consentPolicies: {
    serviceProcessing: "service-processing.v1", humanReview: "human-review.v1", anonymizedResearch: "anonymized-research.v1",
  } } }));
  await page.route("**/api/v1/cases", route => route.fulfill({ status: 201, json: { case: { id: "case-1" }, accessToken: "secret" } }));
  await page.route("**/api/v1/cases/case-1/vehicle-evidence", route => route.fulfill({ json: { identityConflicts: [], reliableForAnalysis: true } }));
  await page.route("**/api/v1/cases/case-1/listing-captures", route => route.fulfill({ json: view }));
  await page.route("**/api/v1/cases/case-1/listing-captures/text", route => route.fulfill({ status: 202,
    json: { ...savedListing, captures: [readCapture] } }));
  await page.route("**/api/v1/cases/case-1/listing-captures/listing-1/pages/1/image", route => route.fulfill({
    contentType: "image/png", body: PAGE_IMAGE }));
  await page.goto("/");
  await page.getByLabel(/your zip code/i).fill("27601");
  await page.getByLabel(/dealer city/i).fill("27604");
  await page.getByLabel("Dealer state").selectOption("NC");
  await page.getByLabel(/^vin$/i).fill("1HGCM82633A004352");
  await page.getByRole("button", { name: /start my case/i }).click();
  const panel = page.locator(".listing-capture");
  await expect(panel.getByRole("heading", { name: "The dealer's ad for this car" })).toBeVisible();
  return panel;
}

async function expectUsableAtNarrowWidth(page: Page, panel: ReturnType<Page["locator"]>) {
  // Let the theme cross-fade and entrance animation settle before measuring colours and layout.
  await page.waitForTimeout(600);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
  for (const control of await panel.locator("button, input:not([type=checkbox]):not([type=file]), .listing-capture-file, label:has(> input[type=checkbox])").all())
    if (await control.isVisible()) expect((await control.boundingBox())!.height).toBeGreaterThanOrEqual(44);
  const results = await new AxeBuilder({ page }).include(".listing-capture").withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"]).analyze();
  expect(results.violations.map(violation => ({ id: violation.id, nodes: violation.nodes.map(node => node.html) }))).toEqual([]);
}

// The narrow and narrow-dark projects run each state in both themes.
test("a saved ad with its price check and fine print reads at 360 px", async ({ page }) => {
  const panel = await openCase(page, savedListing);
  await expect(panel.getByText(/Your saved ad: \$23,995/)).toBeVisible();
  await expect(panel.getByText(savedListing.check!.deskQuestion!)).toBeVisible();
  await expect(panel.getByText(/loyalty rebate/)).toBeVisible();
  await expectUsableAtNarrowWidth(page, panel);
});

test("a saved ad for a different VIN offers the choice of car at 360 px", async ({ page }) => {
  const panel = await openCase(page, conflictingListing);
  await expect(panel.getByRole("alert")).toContainText("This ad is for VIN 1M8GDM9AXKP042788");
  await expect(panel.getByRole("button", { name: "Keep this case's car" })).toBeVisible();
  await expectUsableAtNarrowWidth(page, panel);
});

test("pasted ad text is checked field by field against its outlined source at 360 px", async ({ page }) => {
  const panel = await openCase(page, { captures: [], snapshot: null, check: null, identityConflicts: [] });
  await panel.getByRole("button", { name: "Paste the ad's text" }).click();
  await panel.getByLabel(/The ad's text/).fill("2021 Honda Accord EX-L\nOur Price: $23,995*");
  await panel.getByRole("button", { name: "Read this text" }).click();
  await expect(panel.getByRole("heading", { name: "Check each field against the ad" })).toBeVisible();
  await expect(panel.getByLabel("Price the ad shows")).toHaveValue("23,995");
  await panel.getByRole("button", { name: /Show where the dealer was read/ }).click();
  await expect(panel.getByRole("img", { name: /where the dealer was read/ })).toBeVisible();
  await expectUsableAtNarrowWidth(page, panel);
});

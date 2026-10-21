import { AxeBuilder, expect, test, type Page } from "./fixtures";
import { loanView } from "../src/LoanPreApprovals.fixture";

// Ticket 129: every step of the loan pre-approvals panel, one-handed at 360 px, in both themes.
async function openCase(page: Page, view: unknown, refusal?: unknown) {
  await page.route("**/api/v1/**", route => route.fulfill({ status: 204, body: "" }));
  await page.route("**/api/v1/intake-options", route => route.fulfill({ json: { supported: ["US-NC"], consentPolicies: {
    serviceProcessing: "service-processing.v1", humanReview: "human-review.v1", anonymizedResearch: "anonymized-research.v1",
  } } }));
  await page.route("**/api/v1/cases", route => route.fulfill({ status: 201, json: { case: { id: "case-1" }, accessToken: "secret" } }));
  await page.route("**/api/v1/cases/case-1/vehicle-evidence", route => route.fulfill({ json: { identityConflicts: [], reliableForAnalysis: true } }));
  await page.route("**/api/v1/cases/case-1/loan", route => route.request().method() === "PUT" && refusal
    ? route.fulfill({ status: 400, json: refusal }) : route.fulfill({ json: view }));
  await page.goto("/");
  await page.getByLabel(/your zip code/i).fill("27601");
  await page.getByLabel(/dealer city/i).fill("27604");
  await page.getByLabel("Dealer state").selectOption("NC");
  await page.getByLabel(/^vin$/i).fill("1HGCM82633A004352");
  await page.getByRole("button", { name: /start my case/i }).click();
  const panel = page.locator(".loan-pre-approvals");
  await expect(panel.getByRole("heading", { name: "Loan pre-approvals" })).toBeVisible();
  return panel;
}

async function expectUsableAtNarrowWidth(page: Page, panel: ReturnType<Page["locator"]>) {
  // Let the theme cross-fade and entrance animation settle before measuring colours and layout.
  await page.waitForTimeout(600);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
  for (const control of await panel.locator("button, a.loan-continue, input:not([type=radio]):not([type=checkbox]), select, label:has(> input[type=radio])").all())
    if (await control.isVisible()) expect((await control.boundingBox())!.height).toBeGreaterThanOrEqual(44);
  const results = await new AxeBuilder({ page }).include(".loan-pre-approvals").withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"]).analyze();
  expect(results.violations.map(violation => ({ id: violation.id, nodes: violation.nodes.map(node => node.html) }))).toEqual([]);
}

test("starting a loan application from a case works one-handed at 360 px", async ({ page }) => {
  const panel = await openCase(page, loanView("EMPTY"));
  await expect(panel.getByText(/a pre-approval is not a\s+loan/)).toBeVisible();
  await expectUsableAtNarrowWidth(page, panel);
});

test("the loan application with field errors works one-handed at 360 px", async ({ page }) => {
  const panel = await openCase(page, loanView("EMPTY"), { detail: "Check the highlighted loan details.", errors: [
    { field: "amountMinor", message: "Enter the amount you want to borrow, up to $500,000." },
    { field: "housing", message: "Choose whether you own, rent or live another way." },
    { field: "monthlyHousingCostMinor", message: "Enter what you pay for housing each month, up to $100,000; enter 0 if nothing." }] });
  await panel.getByRole("button", { name: "Start a loan application" }).click();
  await expect(panel.getByLabel("Make")).toHaveValue("Honda");
  await panel.getByRole("button", { name: "Save application" }).click();
  await expect(panel.getByText("Enter the amount you want to borrow, up to $500,000.")).toBeVisible();
  await expectUsableAtNarrowWidth(page, panel);
});

test("the written credit authorization works one-handed at 360 px", async ({ page }) => {
  const panel = await openCase(page, loanView("APPLIED"));
  await expect(panel.getByText("$30,000 over 60 months")).toBeVisible();
  await panel.getByRole("button", { name: "Get pre-approvals" }).click();
  await expect(panel.getByText(/Fair Credit Reporting Act/)).toBeVisible();
  await expect(panel.getByRole("button", { name: "Send and get pre-approvals" })).toBeDisabled();
  await panel.getByRole("checkbox", { name: /I give this written permission/ }).check();
  await expectUsableAtNarrowWidth(page, panel);
});

test("ordered offers, a chosen pre-approval, declines, expired offers and an offer of the Buyer's own work one-handed at 360 px", async ({ page }) => {
  const panel = await openCase(page, loanView("CHOSEN"));
  await expect(panel.getByRole("list", { name: "Offers for the amount and term you asked for" }).getByRole("listitem").first())
    .toContainText("Space Coast Community Credit Union of Brevard County");
  await expect(panel.getByRole("region", { name: "Your own financing" })).toContainText("Coastal Auto Finance");
  await expect(panel.getByText(/Companies shown here may pay otdog/)).toBeVisible();
  await expect(panel.getByRole("list", { name: "Lenders that did not make an offer" })).toContainText("Keys Bank");
  await panel.getByRole("button", { name: "Add an offer I already have" }).click();
  await expect(panel.getByLabel("Good until (optional)")).toBeVisible();
  await expectUsableAtNarrowWidth(page, panel);
});

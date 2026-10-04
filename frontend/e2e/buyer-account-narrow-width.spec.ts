import AxeBuilder from "@axe-core/playwright";
import { expect, Page, test } from "@playwright/test";

const ACCOUNT = {
  contact: { email: "a.very.long.buyer.address.for.layout@example-mailbox-provider.com", firstName: null, lastName: null, phone: null },
  createdAt: "2026-10-03T00:00:00Z",
  sessionExpiresAt: "2026-10-17T00:00:00Z",
  cases: [{ caseId: "8f1c2d3e-4b5a-6978-8a9b-0c1d2e3f4a5b", attachedAt: "2026-10-03T00:00:00Z", state: "ACTIVE" }],
};

/** The layout gate needs no real service, only the account shapes the panel renders from. */
async function stubAccountApi(page: Page) {
  let preferences = ["DEALER_REPLY", "DRAFT_NEEDS_APPROVAL", "MANDATE_EXPIRING", "APPOINTMENT", "ACCOUNT_SECURITY"]
    .map(type => ({ type, enabled: true, mandatory: type === "ACCOUNT_SECURITY" }));
  await page.route("**/api/v1/**", route => route.fulfill({ status: 204, body: "" }));
  await page.route("**/api/v1/intake-options", route => route.fulfill({ json: { supported: ["US-NC"], consentPolicies: {} } }));
  await page.route("**/api/v1/account/sign-in/verify", route =>
    route.fulfill({ json: { sessionToken: "session-1", expiresAt: "2026-10-17T00:00:00Z" } }));
  await page.route("**/api/v1/account", route => route.fulfill({ json: ACCOUNT }));
  await page.route("**/api/v1/account/notifications", route => {
    if (route.request().method() === "PUT") {
      const change = route.request().postDataJSON();
      preferences = preferences.map(value => value.type === change.type ? { ...value, enabled: change.enabled } : value);
    }
    return route.fulfill({ json: preferences });
  });
  await page.route("**/api/v1/account/deletion", route => route.fulfill({ status: 202,
    json: { state: "DELETION_PENDING", failureCode: null, cases: [{ caseId: ACCOUNT.cases[0].caseId, state: "DELETION_PENDING" }] } }));
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
    .include("[aria-label='Buyer Account']")
    .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
    .analyze();
  expect(results.violations).toEqual([]);
}

test("the signed-out account panel requests a link at 360 px without storing anything", async ({ page }) => {
  await stubAccountApi(page);
  await page.goto("/");
  const panel = page.getByRole("region", { name: "Buyer Account" });
  await expect(panel.getByRole("button", { name: "Email me a sign-in link" })).toBeVisible();
  await expectNoOverflowOrViolations(page);
  expect(await page.evaluate(() => localStorage.length + sessionStorage.length)).toBe(0);
});

test("signing in, the account screen and the deletion receipt fit 360 px", async ({ page }) => {
  await stubAccountApi(page);
  await page.goto("/#sign-in=link-secret");
  const panel = page.getByRole("region", { name: "Buyer Account" });
  await expect(panel.getByRole("button", { name: "Finish signing in" })).toBeVisible();
  // The single-use secret leaves the address bar before anything else happens.
  await expect.poll(() => new URL(page.url()).hash).toBe("");
  await panel.getByRole("button", { name: "Finish signing in" }).click();
  await expect(panel.getByText(ACCOUNT.contact.email)).toBeVisible();
  await expect(panel.getByRole("list", { name: "Attached Buying Cases" })).toContainText("Open");
  await expect(panel.getByLabel("Account security (required)")).toBeChecked();
  await expect(panel.getByLabel("Account security (required)")).toBeDisabled();
  for (const label of ["Dealer replies", "Drafts needing approval", "Mandate expiry", "Appointments"]) {
    await expect(panel.getByLabel(label)).toBeChecked();
    await panel.getByLabel(label).click();
    await expect(panel.getByLabel(label)).not.toBeChecked();
    await expect(panel.getByLabel(label)).toBeEnabled();
  }
  await expectNoOverflowOrViolations(page);

  // The session survives a page reload.
  await page.reload();
  await expect(panel.getByText(ACCOUNT.contact.email)).toBeVisible();
  await expect(panel.getByLabel("Dealer replies")).not.toBeChecked();

  await panel.getByLabel(/permanently delete my Buyer Account/).check();
  await panel.getByRole("button", { name: "Delete Buyer Account" }).click();
  await expect(panel.getByRole("list", { name: "Attached cases being deleted" })).toBeVisible();
  await expectNoOverflowOrViolations(page);
  expect(await page.evaluate(() => localStorage.length)).toBe(0);
});

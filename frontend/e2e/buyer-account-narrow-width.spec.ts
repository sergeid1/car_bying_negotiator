import AxeBuilder from "@axe-core/playwright";
import { expect, Page, test } from "@playwright/test";

const ACCOUNT = {
  contact: { email: "a.very.long.buyer.address.for.layout@example-mailbox-provider.com", firstName: null, lastName: null, phone: null },
  createdAt: "2026-10-03T00:00:00Z",
  sessionExpiresAt: "2026-10-17T00:00:00Z",
  cases: [
    { caseId: "8f1c2d3e-4b5a-6978-8a9b-0c1d2e3f4a5b", attachedAt: "2026-10-03T00:00:00Z", state: "ACTIVE",
      startedAt: "2026-10-03T15:00:00Z", vehicle: "2023 Mercedes-Benz GLE-Class GLE 450 4MATIC SUV with an unusually long trim",
      dealer: "Sunshine State Luxury Imports of Greater Fort Lauderdale" },
    { caseId: "1a2b3c4d-4b5a-6978-8a9b-0c1d2e3f4a5b", attachedAt: "2026-10-04T00:00:00Z", state: "ACTIVE",
      startedAt: "2026-10-04T15:00:00Z", vehicle: null, dealer: "Dealer ZIP 33301" },
    { caseId: "9e8d7c6b-4b5a-6978-8a9b-0c1d2e3f4a5b", attachedAt: "2026-09-01T00:00:00Z", state: "EXPIRED",
      startedAt: "2026-09-01T15:00:00Z", vehicle: "2019 Mazda CX-5", dealer: null },
  ],
};

/** The layout gate needs no real service, only the account shapes the panel renders from. */
async function stubAccountApi(page: Page) {
  let preferences = ["DEALER_REPLY", "DRAFT_NEEDS_APPROVAL", "MANDATE_EXPIRING", "APPOINTMENT", "ACCOUNT_SECURITY"]
    .map(type => ({ type, enabled: true, mandatory: type === "ACCOUNT_SECURITY" }));
  await page.route("**/api/v1/**", route => route.fulfill({ status: 204, body: "" }));
  await page.route("**/api/v1/intake-options", route => route.fulfill({ json: { supported: ["US-NC"], consentPolicies: {} } }));
  await page.route("**/api/v1/account/sign-in/verify", route =>
    route.fulfill({ headers: { "Set-Cookie": "account_session=session-1; HttpOnly; Secure; SameSite=Strict; Path=/api/v1; Max-Age=1209600" },
      json: { expiresAt: "2026-10-17T00:00:00Z" } }));
  await page.route("**/api/v1/account", route => route.request().headers().cookie?.includes("account_session=session-1")
    ? route.fulfill({ json: ACCOUNT }) : route.fulfill({ status: 401, json: { code: "SESSION_ENDED" } }));
  await page.route("**/api/v1/account/notifications", route => {
    if (route.request().method() === "PUT") {
      expect(route.request().headers()["account-request"]).toBe("1");
      expect(route.request().headers()["account-session-token"]).toBeUndefined();
      const change = route.request().postDataJSON();
      preferences = preferences.map(value => value.type === change.type ? { ...value, enabled: change.enabled } : value);
    }
    return route.fulfill({ json: preferences });
  });
  await page.route("**/api/v1/account/cases/*/open", route => {
    expect(route.request().method()).toBe("POST");
    expect(route.request().headers()["account-request"]).toBe("1");
    return route.fulfill({ json: { caseId: ACCOUNT.cases[0].caseId, accessToken: `acct.${ACCOUNT.cases[0].caseId}.1999999999.signature`,
      accessExpiresAt: "2026-10-17T00:00:00Z" } });
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
  const cookie = (await page.context().cookies()).find(cookie => cookie.name === "account_session");
  expect(cookie).toMatchObject({ httpOnly: true, secure: true, sameSite: "Strict", path: "/api/v1" });
  expect(await page.evaluate(() => document.cookie)).not.toContain("account_session");
  expect(await page.evaluate(() => localStorage.length + sessionStorage.length)).toBe(0);
  const cases = panel.getByRole("list", { name: "Your Buying Cases" });
  await expect(cases.getByRole("button", { name: /^Open / })).toHaveCount(2);
  await expect(cases).toContainText("Unavailable: Expired");
  await expect(cases).toContainText("Vehicle not entered yet");
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

test("a case opens from the account list at 360 px without showing a case link", async ({ page }) => {
  await stubAccountApi(page);
  await page.context().addCookies([{ name: "account_session", value: "session-1", domain: "127.0.0.1", path: "/api/v1",
    httpOnly: true, secure: true, sameSite: "Strict" }]);
  await page.goto("/");
  const panel = page.getByRole("region", { name: "Buyer Account" });
  await expect(page.getByText(/this case will be saved to your Buyer Account/)).toBeVisible();
  await panel.getByRole("button", { name: /^Open 2023 Mercedes-Benz/ }).click();
  await expect(page.getByRole("region", { name: "Opened from your Buyer Account" })).toBeVisible();
  await expect(page.getByRole("region", { name: "Private return link" })).toHaveCount(0);
  await expectNoOverflowOrViolations(page);
  expect(await page.evaluate(() => localStorage.length + sessionStorage.length)).toBe(0);
});

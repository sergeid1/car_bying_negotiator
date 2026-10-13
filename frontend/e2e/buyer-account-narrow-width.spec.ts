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
  await page.route("**/api/v1/account/sign-out", route => route.fulfill({ status: 204,
    headers: { "Set-Cookie": "account_session=; HttpOnly; SameSite=Strict; Path=/api/v1; Max-Age=0" } }));
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

/** The account lives in a side panel opened from the header: "Sign in", or the Buyer's email once signed in. */
async function openAccountPanel(page: Page, name: string = "Sign in") {
  await page.getByRole("banner").getByRole("button", { name, exact: true }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
}

async function expectNoOverflowOrViolations(page: Page) {
  // A side panel slides in from the right; measure once it has arrived.
  await page.evaluate(() => Promise.all(Array.from(document.querySelectorAll(".side-panel, .side-panel-backdrop"))
    .flatMap(element => element.getAnimations()).map(animation => animation.finished)));
  await page.evaluate(() => window.scrollTo({ top: 0, behavior: "instant" }));
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
    .include(".case-link-access")
    .include(".masthead")
    .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
    .analyze();
  expect(results.violations).toEqual([]);
}

for (const colorScheme of ["light", "dark"] as const) {
  test(`the signed-out panel shows one email box and a collapsed case-link disclosure at 360 px in the ${colorScheme} theme`, async ({ page }) => {
    await page.emulateMedia({ colorScheme });
    await stubAccountApi(page);
    await page.goto("/");
    const header = page.getByRole("banner");
    await expect(header.getByRole("button", { name: "This case" })).toHaveCount(0);
    await expect(header.getByRole("button", { name: "Your case" })).toHaveCount(0);
    await openAccountPanel(page);
    const dialog = page.getByRole("dialog", { name: "Log in" });
    const panel = page.getByRole("region", { name: "Buyer Account" });
    await expect(dialog).toHaveClass(/sign-in-card/);
    await expect(panel.getByRole("button", { name: "Continue with email" })).toBeVisible();
    await expect(dialog.getByRole("textbox")).toHaveCount(1);
    await expect(panel.getByRole("button", { name: "Use a password instead" })).toBeVisible();
    await expect(dialog.getByRole("region", { name: "Open an existing Buying Case" })).toBeHidden();
    await expectNoOverflowOrViolations(page);
    await dialog.getByText("Have a private case link?").click();
    await expect(dialog.getByRole("region", { name: "Open an existing Buying Case" })).toBeVisible();
    await expect(dialog.getByRole("region", { name: "Recover Buying Case access" })).toContainText("Lost the link?");
    await expectNoOverflowOrViolations(page);
    expect(await page.evaluate(() => localStorage.length + sessionStorage.length)).toBe(0);
  });
}

for (const width of [360, 1280]) {
  test(`requesting an emailed link and signing out from either place at ${width} px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await stubAccountApi(page);
    // Mail capture lives only in this browser fixture, never in the deployed application.
    const mailbox: string[] = [];
    await page.route("**/api/v1/account/sign-in-requests", route => {
      expect(route.request().postDataJSON().email).toBe(ACCOUNT.contact.email);
      mailbox.push("/#sign-in=fixture-single-use-link");
      return route.fulfill({ json: { status: "Check your email for a single-use sign-in link." } });
    });
    for (const signOutLocation of ["banner", "region"]) {
      await page.goto("/");
      const header = page.getByRole("banner");
      const panel = page.getByRole("region", { name: "Buyer Account" });
      await header.getByRole("button", { name: "Sign in", exact: true }).click();
      await expect(page.getByRole("dialog", { name: "Log in" })).toBeFocused();
      await panel.getByLabel("Email", { exact: true }).fill(ACCOUNT.contact.email);
      await panel.getByRole("button", { name: "Continue with email" }).click();
      await expect(panel.getByRole("status")).toHaveText("Check your email. The link works for 15 minutes.");
      await expect(panel.getByRole("button", { name: "Send again" })).toBeVisible();
      await expectNoOverflowOrViolations(page);
      expect(mailbox).toHaveLength(1);
      // The emailed link opens the account panel; it closes once the Buyer is signed in.
      await page.goto(mailbox.pop()!);
      await panel.getByRole("button", { name: "Finish signing in" }).click();
      await expect(header.getByRole("button", { name: ACCOUNT.contact.email })).toBeVisible();
      await expect(page.getByRole("dialog")).toHaveCount(0);
      await expectNoOverflowOrViolations(page);
      if (signOutLocation === "region") await openAccountPanel(page, ACCOUNT.contact.email);
      await (signOutLocation === "banner" ? header : panel).getByRole("button", { name: "Sign out", exact: true }).click();
      if (signOutLocation === "banner") await openAccountPanel(page);
      await expect(page.getByRole("dialog", { name: "Log in" })).toBeVisible();
      await expect(panel.getByRole("button", { name: "Continue with email" })).toBeVisible();
      await expectNoOverflowOrViolations(page);
    }
  });
}

test("signing in, the account screen and the deletion receipt fit 360 px", async ({ page }) => {
  await stubAccountApi(page);
  await page.goto("/#sign-in=link-secret");
  const panel = page.getByRole("region", { name: "Buyer Account" });
  await expect(panel.getByRole("button", { name: "Finish signing in" })).toBeVisible();
  // The single-use secret leaves the address bar before anything else happens.
  await expect.poll(() => new URL(page.url()).hash).toBe("");
  await panel.getByRole("button", { name: "Finish signing in" }).click();
  await openAccountPanel(page, ACCOUNT.contact.email);
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
  await openAccountPanel(page, ACCOUNT.contact.email);
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
  await openAccountPanel(page, ACCOUNT.contact.email);
  await panel.getByRole("button", { name: /^Open 2023 Mercedes-Benz/ }).click();
  await expect(page.getByRole("region", { name: "Opened from your Buyer Account" })).toBeVisible();
  await expect(page.getByRole("region", { name: "Private return link" })).toHaveCount(0);
  await expectNoOverflowOrViolations(page);
  expect(await page.evaluate(() => localStorage.length + sessionStorage.length)).toBe(0);
});

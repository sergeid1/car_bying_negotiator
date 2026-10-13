import AxeBuilder from "@axe-core/playwright";
import { expect, Page, test } from "@playwright/test";

async function stub(page: Page, providers = ["GOOGLE", "APPLE"], failure?: string) {
  let signedIn = false;
  await page.route("**/api/v1/**", async route => {
    const path = new URL(route.request().url()).pathname;
    if (path === "/api/v1/account") return route.fulfill({ status: signedIn ? 200 : 401,
      json: signedIn ? { contact: { email: "buyer@example.com" }, cases: [] } : {} });
    if (path.endsWith("/social-sign-in/providers")) return route.fulfill({ json: { providers } });
    if (path.endsWith("/social-sign-in")) {
      expect(route.request().headers()["account-request"]).toBe("1");
      const provider = route.request().postDataJSON().provider;
      return route.fulfill({ json: { authorizationUrl: `https://provider.example/authorize?provider=${provider}` } });
    }
    if (path.endsWith("/social-sign-in/complete")) {
      expect(route.request().headers()["account-request"]).toBe("1");
      const state = route.request().postDataJSON().state;
      expect(["GOOGLE", "APPLE"]).toContain(state);
      if (failure) return route.fulfill({ status: 400, json: { code: "SOCIAL_SIGN_IN_FAILED", detail: failure } });
      signedIn = true;
      return route.fulfill({ json: { expiresAt: "2026-10-27T00:00:00Z", provider: state } });
    }
    if (path.endsWith("/intake-options")) return route.fulfill({ json: { supported: ["US-NC"], consentPolicies: {} } });
    if (path.endsWith("/password")) return route.fulfill({ json: { configured: false } });
    return route.fulfill({ json: [] });
  });
  await page.route("https://provider.example/**", route => route.fulfill({ contentType: "text/html", body: "Provider authorization fixture" }));
}

async function layout(page: Page) {
  const card = page.getByRole("dialog");
  const bounds = await card.boundingBox();
  expect(bounds!.x).toBeGreaterThanOrEqual(16);
  expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(344);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
  expect((await new AxeBuilder({ page }).include("#account-panel")
    .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"]).analyze()).violations).toEqual([]);
}

test("both modes fit 360 px, trap focus, and close to their opener", async ({ page }, testInfo) => {
  await stub(page);
  await page.goto("/");
  const opener = page.getByRole("banner").getByRole("button", { name: "Sign in", exact: true });
  await opener.click();
  const card = page.getByRole("dialog");
  await expect(card).toHaveAccessibleName("Log in");
  await expect(card).toBeFocused();
  await expect(card.getByRole("button", { name: "Continue with Google" })).toBeVisible();
  await card.getByLabel("Email", { exact: true }).fill("buyer@example.com");
  await layout(page);
  await page.screenshot({ path: testInfo.outputPath("log-in.png") });
  await card.getByRole("button", { name: "Close Log in" }).focus();
  await page.keyboard.press("Shift+Tab");
  await expect(card.getByText("Have a private case link?")).toBeFocused();
  await page.keyboard.press("Tab");
  await expect(card.getByRole("button", { name: "Close Log in" })).toBeFocused();
  await card.getByRole("button", { name: "Sign up", exact: true }).click();
  await expect(card).toHaveAccessibleName("Sign up");
  await expect(card.getByLabel("Email", { exact: true })).toHaveValue("buyer@example.com");
  await expect(card.getByText("Have a private case link?")).toBeHidden();
  await expect(card.getByText("Optional. Keeps your cases in one place.")).toBeVisible();
  const order = await card.locator(".sign-in input, .sign-in-provider").evaluateAll(elements => elements.map(el => el.tagName));
  expect(order).toEqual(["BUTTON", "BUTTON", "INPUT"]);
  await layout(page);
  await page.screenshot({ path: testInfo.outputPath("sign-up.png") });
  await page.setViewportSize({ width: 360, height: 320 });
  const smallBounds = await card.boundingBox();
  expect(smallBounds!.y).toBeGreaterThanOrEqual(16);
  expect(smallBounds!.y + smallBounds!.height).toBeLessThanOrEqual(304);
  expect(await card.locator(".side-panel-body").evaluate(el => el.scrollHeight > el.clientHeight)).toBe(true);
  await card.getByRole("button", { name: "Continue with email" }).scrollIntoViewIfNeeded();
  await expect(card.getByRole("button", { name: "Continue with email" })).toBeInViewport();
  await page.setViewportSize({ width: 360, height: 780 });
  await card.getByRole("button", { name: "Log in", exact: true }).click();
  await expect(card).toHaveAccessibleName("Log in");
  await page.keyboard.press("Escape");
  await expect(opener).toBeFocused();
  await opener.click();
  await page.locator(".side-panel-backdrop:not([hidden])").click({ position: { x: 2, y: 2 } });
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(opener).toBeFocused();
});

for (const provider of ["GOOGLE", "APPLE"]) {
  test(`${provider} starts a top-level navigation and finishes the returned state`, async ({ page, baseURL }) => {
    await stub(page, [provider]);
    await page.goto("/");
    await page.getByRole("banner").getByRole("button", { name: "Sign in", exact: true }).click();
    const name = provider === "GOOGLE" ? "Google" : "Apple";
    const other = provider === "GOOGLE" ? "Apple" : "Google";
    await expect(page.getByRole("button", { name: `Continue with ${other}` })).toHaveCount(0);
    await page.getByRole("button", { name: `Continue with ${name}` }).click();
    await expect(page).toHaveURL(`https://provider.example/authorize?provider=${provider}`);
    await page.goto(`${baseURL}/#social-sign-in=${provider}`);
    await expect(page.getByRole("banner").getByRole("button", { name: "buyer@example.com" })).toBeVisible();
    await expect(page.getByRole("dialog")).toHaveCount(0);
    await expect(page.getByRole("status")).toHaveText(`Signed in with ${name}.`);
    expect(new URL(page.url()).hash).toBe("");
    await page.getByRole("banner").getByRole("button", { name: "buyer@example.com" }).click();
    await expect(page.getByRole("dialog", { name: "Your account" })).not.toHaveClass(/sign-in-card/);
  });
}

for (const reason of ["cancelled", "failed", "complete-failed"]) {
  test(`${reason} returns to a usable card without the hash`, async ({ page }) => {
    const detail = "Sign-in with Apple could not be completed. Try again or continue with your email.";
    await stub(page, ["GOOGLE", "APPLE"], reason === "complete-failed" ? detail : undefined);
    await page.goto(reason === "complete-failed" ? "/#social-sign-in=APPLE" : `/#social-sign-in-error=${reason}`);
    const card = page.getByRole("dialog", { name: "Log in" });
    await expect(card.getByRole("alert")).toHaveText(reason === "cancelled" ? "Sign-in with your provider was cancelled."
      : reason === "complete-failed" ? detail : "Sign-in with your provider could not be completed. Try again or continue with your email.");
    await expect(card.getByRole("button", { name: "Continue with email" })).toBeEnabled();
    expect(new URL(page.url()).hash).toBe("");
    await layout(page);
  });
}

test("no configured providers hides the divider in both modes", async ({ page }) => {
  await stub(page, []);
  await page.goto("/");
  await page.getByRole("banner").getByRole("button", { name: "Sign in", exact: true }).click();
  const card = page.getByRole("dialog");
  await expect(card.getByRole("button", { name: "Continue with email" })).toBeVisible();
  await expect(card.locator(".sign-in-divider")).toHaveCount(0);
  await expect(card.getByRole("button", { name: /Continue with (Google|Apple)/ })).toHaveCount(0);
  await card.getByRole("button", { name: "Sign up", exact: true }).click();
  await expect(card.locator(".sign-in-divider")).toHaveCount(0);
});

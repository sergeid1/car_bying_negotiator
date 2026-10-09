import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";

for (const width of [360, 1280]) {
  test(`sets, signs in, changes and removes a password at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    let signedIn = true, password = "";
    const account = { contact: { email: "buyer@example.com" }, cases: [], createdAt: "", sessionExpiresAt: "" };
    await page.route("**/api/v1/**", async route => {
      const path = new URL(route.request().url()).pathname, method = route.request().method();
      if (path === "/api/v1/account") return route.fulfill({ status: signedIn ? 200 : 401, json: signedIn ? account : {} });
      if (path === "/api/v1/account/password") {
        if (method !== "GET") {
          expect(route.request().headers()["account-request"]).toBe("1");
          password = method === "DELETE" ? "" : route.request().postDataJSON().password;
        }
        return route.fulfill({ json: { configured: !!password } });
      }
      if (path === "/api/v1/account/password/sign-in") {
        expect(route.request().postDataJSON()).toEqual({ email: account.contact.email, password });
        expect(route.request().headers()["account-request"]).toBe("1"); signedIn = true;
        return route.fulfill({ json: { expiresAt: "2026-10-23T12:00:00Z" }, headers: {
          "Set-Cookie": "account_session=password-session; HttpOnly; Secure; SameSite=Strict; Path=/api/v1; Max-Age=1209600" } });
      }
      if (path === "/api/v1/account/sign-out") { signedIn = false; return route.fulfill({ status: 204 }); }
      if (path.endsWith("/notifications") || path.endsWith("/passkeys")) return route.fulfill({ json: [] });
      if (path.endsWith("/intake-options")) return route.fulfill({ json: { supported: ["US-NC"], consentPolicies: {} } });
      return route.fulfill({ json: {} });
    });
    await page.goto("/");
    const panel = page.getByRole("region", { name: "Buyer Account" });
    await panel.getByLabel("New password", { exact: true }).fill("a sufficiently long phrase");
    await panel.getByRole("button", { name: "Set password", exact: true }).click();
    await expect(panel.getByText("A password is set.")).toBeVisible();
    await panel.getByRole("button", { name: "Sign out", exact: true }).click();
    await panel.getByLabel("Email for password sign-in", { exact: true }).fill(account.contact.email);
    await panel.getByLabel("Password", { exact: true }).fill(password);
    await panel.getByRole("button", { name: "Sign in with a password" }).click();
    await expect(panel.getByText("A password is set.")).toBeVisible();
    const cookie = (await page.context().cookies()).find(value => value.name === "account_session");
    expect(cookie).toMatchObject({ httpOnly: true, secure: true, sameSite: "Strict", path: "/api/v1" });
    await panel.getByLabel("New password", { exact: true }).fill("another sufficiently long phrase");
    await panel.getByRole("button", { name: "Change password" }).click();
    await expect(panel.getByLabel("New password", { exact: true })).toHaveValue("");
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
    expect((await new AxeBuilder({ page }).include("[aria-label='Buyer Account']")
      .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"]).analyze()).violations).toEqual([]);
    await panel.getByRole("button", { name: "Remove password" }).click();
    await expect(panel.getByText("Password removed. Email links and passkeys remain available.")).toBeVisible();
    expect(await page.evaluate(() => localStorage.length + sessionStorage.length)).toBe(0);
  });
}

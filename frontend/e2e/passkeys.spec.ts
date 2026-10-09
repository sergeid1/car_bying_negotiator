import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";
import { randomBytes, randomUUID } from "node:crypto";

for (const width of [360, 1280]) {
  test(`virtual authenticator adds, signs in and removes a passkey at ${width}px`, async ({ page, baseURL }) => {
    const origin = new URL(baseURL!); origin.hostname = "localhost";
    await page.setViewportSize({ width, height: 900 });
    const cdp = await page.context().newCDPSession(page);
    await cdp.send("WebAuthn.enable");
    const { authenticatorId } = await cdp.send("WebAuthn.addVirtualAuthenticator", { options: {
      protocol: "ctap2", ctap2Version: "ctap2_1", transport: "internal", hasResidentKey: true,
      hasUserVerification: true, isUserVerified: true, automaticPresenceSimulation: true,
    } });
    const account = { contact: { email: "buyer@example.com" }, cases: [],
      createdAt: "2026-10-09T12:00:00Z", sessionExpiresAt: "2026-10-23T12:00:00Z" };
    let keys: { id: string; nickname: string; createdAt: string; lastUsedAt: string | null }[] = [];
    const user = randomBytes(32).toString("base64url");
    const attempts = new Map<string, { challenge: string; type: string }>();
    let registeredId = "";
    let signIns = 0;
    const violations: string[] = [];
    await page.exposeFunction("passkeyCspViolation", (directive: string) => violations.push(directive));
    await page.addInitScript(() => document.addEventListener("securitypolicyviolation", event => {
      void (window as unknown as { passkeyCspViolation: (value: string) => void }).passkeyCspViolation(event.effectiveDirective);
    }));
    await page.route("**/api/v1/**", async route => {
      const path = new URL(route.request().url()).pathname;
      const method = route.request().method();
      const signedIn = route.request().headers().cookie?.includes("account_session=browser-session");
      const root = "/api/v1/account/passkeys";
      if (path === "/api/v1/account") return route.fulfill({ status: signedIn ? 200 : 401, json: signedIn ? account : {} });
      if (path === "/api/v1/account/sign-out") return route.fulfill({ status: 204, headers: {
        "Set-Cookie": "account_session=; HttpOnly; Secure; SameSite=Strict; Path=/api/v1; Max-Age=0" } });
      if (path === root) return route.fulfill({ json: keys });
      if (path.endsWith("/options") && path.startsWith(root)) {
        const register = path.includes("registration");
        if (register) { expect(signedIn).toBe(true); expect(route.request().headers()["account-request"]).toBe("1"); }
        const attemptId = randomUUID(), challenge = randomBytes(32).toString("base64url");
        attempts.set(attemptId, { challenge, type: register ? "webauthn.create" : "webauthn.get" });
        return route.fulfill({ json: { attemptId, publicKey: register ? {
          challenge, rp: { id: "localhost", name: "Deal X-Ray" }, user: { id: user, name: "Buyer Account", displayName: "Buyer Account" },
          pubKeyCredParams: [{ type: "public-key", alg: -7 }, { type: "public-key", alg: -257 }], timeout: 60000, attestation: "none",
          authenticatorSelection: { residentKey: "required", userVerification: "required" },
        } : { challenge, rpId: "localhost", userVerification: "required", timeout: 60000 } } });
      }
      if (path === `${root}/registration` || path === `${root}/sign-in`) {
        const body = route.request().postDataJSON();
        const attempt = attempts.get(body.attemptId)!; expect(attempt).toBeTruthy(); attempts.delete(body.attemptId);
        const credential = JSON.parse(body.credential);
        const client = JSON.parse(Buffer.from(credential.response.clientDataJSON, "base64url").toString());
        expect(client).toMatchObject({ type: attempt.type, challenge: attempt.challenge, origin: new URL(route.request().url()).origin });
        if (path.endsWith("/registration")) {
          expect(credential.response.attestationObject).toBeTruthy(); registeredId = credential.id;
          keys = [{ id: "metadata-id", nickname: body.nickname, createdAt: account.createdAt, lastUsedAt: null }];
          return route.fulfill({ json: keys });
        }
        expect(credential.id).toBe(registeredId);
        expect(credential.response.userHandle).toBe(user);
        expect(credential.response.signature).toBeTruthy(); signIns++;
        keys[0].lastUsedAt = "2026-10-10T12:00:00Z";
        return route.fulfill({ json: { expiresAt: account.sessionExpiresAt }, headers: {
          "Set-Cookie": "account_session=browser-session; HttpOnly; Secure; SameSite=Strict; Path=/api/v1; Max-Age=1209600" } });
      }
      if (path === `${root}/metadata-id` && method === "DELETE") {
        expect(signedIn).toBe(true); expect(route.request().headers()["account-request"]).toBe("1");
        keys = []; return route.fulfill({ json: keys });
      }
      if (path.endsWith("/notifications")) return route.fulfill({ json: [] });
      if (path.endsWith("/intake-options")) return route.fulfill({ json: { supported: ["US-NC"], consentPolicies: {} } });
      return route.fulfill({ json: {} });
    });
    await page.context().addCookies([{ name: "account_session", value: "browser-session", domain: "localhost",
      path: "/api/v1", httpOnly: true, secure: true, sameSite: "Strict" }]);
    await page.goto(origin.href);
    const panel = page.getByRole("region", { name: "Buyer Account" });
    const nickname = "My very long device name with a roaming authenticator and a useful reminder";
    await panel.getByLabel("Passkey nickname").fill(nickname);
    await panel.getByRole("button", { name: "Add a passkey" }).click();
    await expect(panel.getByRole("list", { name: "Your passkeys" })).toContainText(nickname);
    expect((await cdp.send("WebAuthn.getCredentials", { authenticatorId })).credentials).toHaveLength(1);
    await panel.getByRole("button", { name: "Sign out", exact: true }).click();
    await panel.getByRole("button", { name: "Sign in with a passkey" }).click();
    await expect(panel.getByRole("status")).toHaveText("Signed in with a passkey.");
    expect(signIns).toBe(1);
    await expect(panel.getByRole("list", { name: "Your passkeys" })).toContainText("Last used");
    const cookie = (await page.context().cookies()).find(value => value.name === "account_session");
    expect(cookie).toMatchObject({ httpOnly: true, secure: true, sameSite: "Strict", path: "/api/v1" });
    await page.reload();
    await expect(panel.getByRole("list", { name: "Your passkeys" })).toContainText(nickname);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
    expect((await new AxeBuilder({ page }).include("[aria-label='Buyer Account']").withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"]).analyze()).violations).toEqual([]);
    await panel.getByRole("button", { name: `Remove ${nickname}` }).click();
    await expect(panel.getByText("No passkeys added yet.")).toBeVisible();
    expect(await page.evaluate(() => localStorage.length + sessionStorage.length)).toBe(0);
    expect(violations).toEqual([]);
    await cdp.send("WebAuthn.removeVirtualAuthenticator", { authenticatorId });
  });
}

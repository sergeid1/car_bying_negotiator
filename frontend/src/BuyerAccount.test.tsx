import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { readFileSync } from "node:fs";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import { BuyerAccount } from "./BuyerAccount";

function memoryStorage() {
  const values = new Map<string, string>();
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => { values.set(key, value); },
    removeItem: (key: string) => { values.delete(key); },
    get size() { return values.size; },
  };
}
let storage = memoryStorage();
beforeEach(() => {
  storage = memoryStorage();
  Object.defineProperty(window, "localStorage", { value: storage, configurable: true });
  window.history.replaceState(null, "", "/");
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

const account = (cases: { caseId: string; attachedAt: string; state: string }[] = []) => ({
  contact: { email: "buyer@example.com", firstName: null, lastName: null, phone: null },
  createdAt: "2026-10-03T00:00:00Z", sessionExpiresAt: "2026-10-17T00:00:00Z", cases,
});
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

test("requests a sign-in link without storing anything in the browser", async () => {
  const fetcher = vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => String(input) === "/api/v1/account"
    ? json({}, 401) : json({ status: "Check your email for a single-use sign-in link. It expires in 15 minutes." }, 202));
  vi.stubGlobal("fetch", fetcher);
  render(<BuyerAccount />);
  const user = userEvent.setup();
  expect(screen.getByText(/Analysis works without an account/)).toBeVisible();
  expect(screen.queryByLabelText(/password/i)).toBeNull();
  await user.type(await screen.findByLabelText("Email for sign-in"), "buyer@example.com");
  await user.click(screen.getByRole("button", { name: "Email me a sign-in link" }));
  expect(await screen.findByRole("status")).toHaveTextContent("single-use sign-in link");
  const [path, init] = fetcher.mock.calls.find(([path]) => String(path).endsWith("/sign-in-requests"))!;
  expect(path).toBe("/api/v1/account/sign-in-requests");
  expect(JSON.parse(String(init?.body))).toEqual({ email: "buyer@example.com" });
  expect((init?.headers as Record<string, string>)["Idempotency-Key"]).toBeTruthy();
  expect(storage.size).toBe(0);
});

test("finishes sign-in from the emailed link, clears it from the address, and survives a reload", async () => {
  window.history.replaceState(null, "", "/#sign-in=link-secret");
  let signedIn = false;
  const fetcher = vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => {
    if (String(input).endsWith("/verify")) { signedIn = true; return json({ expiresAt: "2026-10-17T00:00:00Z" }); }
    return signedIn ? json(account()) : json({}, 401);
  });
  vi.stubGlobal("fetch", fetcher);
  render(<BuyerAccount />);
  expect(window.location.hash).toBe("");
  await userEvent.setup().click(screen.getByRole("button", { name: "Finish signing in" }));
  expect(await screen.findByText("buyer@example.com")).toBeVisible();
  const verify = fetcher.mock.calls.find(([path]) => String(path).endsWith("/verify"))!;
  expect(JSON.parse(String(verify[1]?.body))).toEqual({ token: "link-secret" });
  expect(storage.size).toBe(0);

  cleanup();
  render(<BuyerAccount />);
  expect(await screen.findByText("buyer@example.com")).toBeVisible();
  expect(fetcher.mock.calls.some(([path]) => path === "/api/v1/account")).toBe(true);
  for (const [, init] of fetcher.mock.calls) expect(init?.headers).not.toHaveProperty("Account-Session-Token");
  expect(storage.size).toBe(0);
});

test.each([
  ["SIGN_IN_LINK_USED", 410, "already used"],
  ["SIGN_IN_LINK_EXPIRED", 410, "has expired"],
  ["SIGN_IN_LINK_UNKNOWN", 404, "not recognized"],
])("a %s link is refused with a clear message", async (code, status, text) => {
  window.history.replaceState(null, "", "/#sign-in=old");
  vi.stubGlobal("fetch", vi.fn(async (input) => String(input) === "/api/v1/account" ? json({}, 401) : json({ code, detail: "server detail" }, status)));
  render(<BuyerAccount />);
  await userEvent.setup().click(screen.getByRole("button", { name: "Finish signing in" }));
  expect(await screen.findByRole("alert")).toHaveTextContent(text);
  expect(screen.queryByRole("button", { name: "Finish signing in" })).toBeNull();
  expect(screen.getByRole("button", { name: "Email me a sign-in link" })).toBeVisible();
  expect(storage.size).toBe(0);
});

test("an ended session on recheck tells the Buyer to sign in again", async () => {
  vi.stubGlobal("fetch", vi.fn(async () => json({ code: "SESSION_ENDED" }, 401)));
  render(<BuyerAccount recheck={1} />);
  expect(await screen.findByRole("alert")).toHaveTextContent("Your sign-in has ended");
  expect(storage.size).toBe(0);
});

test("attaches the open case with its case link and signs out server-side", async () => {
  const fetcher = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path === "/api/v1/account/cases") return json(account([{ caseId: "case-123456789", attachedAt: "", state: "ACTIVE" }]));
    if (path.startsWith("/api/v1/account/sign-out")) return new Response(null, { status: 204 });
    return init?.method === "PUT" ? json(account()) : json(account());
  });
  vi.stubGlobal("fetch", fetcher);
  render(<BuyerAccount caseCredentials={{ caseId: "case-123456789", token: "case-token" }} />);
  const user = userEvent.setup();
  await user.click(await screen.findByRole("button", { name: "Attach this Buying Case" }));
  expect(await screen.findByRole("list", { name: "Attached Buying Cases" })).toHaveTextContent("Open");
  const attach = fetcher.mock.calls.find(([path]) => path === "/api/v1/account/cases")!;
  expect((attach[1]?.headers as Record<string, string>)["Case-Access-Token"]).toBe("case-token");
  expect(screen.queryByRole("button", { name: "Attach this Buying Case" })).toBeNull();
  await user.click(screen.getByRole("button", { name: "Sign out" }));
  expect(await screen.findByRole("button", { name: "Email me a sign-in link" })).toBeVisible();
  expect(fetcher.mock.calls.some(([path, init]) => path === "/api/v1/account/sign-out" && init?.method === "POST")).toBe(true);
  expect(storage.size).toBe(0);
});

test("saves optional contact details and deletes the account only after confirmation", async () => {
  const fetcher = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path === "/api/v1/account/contact") return json({ ...account(), contact: { ...account().contact, ...JSON.parse(String(init?.body)) } });
    if (path === "/api/v1/account/deletion") return json({ state: init?.method === "POST" ? "DELETION_PENDING" : "DELETED", failureCode: null,
      cases: [{ caseId: "case-123456789", state: "DELETION_PENDING" }] }, 202);
    return json(account([{ caseId: "case-123456789", attachedAt: "", state: "ACTIVE" }]));
  });
  vi.stubGlobal("fetch", fetcher);
  render(<BuyerAccount />);
  const user = userEvent.setup();
  await user.type(await screen.findByLabelText("First name (optional)"), "Avery");
  await user.click(screen.getByRole("button", { name: "Save contact details" }));
  expect(await screen.findByRole("status")).toHaveTextContent("Contact details saved.");
  const remove = screen.getByRole("button", { name: "Delete Buyer Account" });
  expect(remove).toBeDisabled();
  await user.click(screen.getByLabelText(/permanently delete my Buyer Account/));
  await user.click(remove);
  expect(await screen.findByText(/no longer accessible/)).toBeVisible();
  expect(screen.getByRole("list", { name: "Attached cases being deleted" })).toHaveTextContent("Being deleted");
  expect(storage.size).toBe(0);
  await user.click(screen.getByRole("button", { name: "Check deletion progress" }));
  const check = fetcher.mock.calls.at(-1)!;
  expect(await screen.findByText(/Active-system removal of your account/)).toBeVisible();
  expect(screen.queryByRole("button", { name: "Check deletion progress" })).toBeNull();
  expect(check[0]).toBe("/api/v1/account/deletion");
  expect(check[1]?.headers).not.toHaveProperty("Account-Session-Token");
  for (const [, init] of fetcher.mock.calls.filter(([, init]) => ["POST", "PUT"].includes(init?.method ?? "")))
    expect(init?.headers).toHaveProperty("Account-Request", "1");
});

test("contact details never reach the console or analytics-style calls", () => {
  const source = readFileSync("src/BuyerAccount.tsx", "utf8");
  for (const api of ["console.", "sendBeacon", "document.cookie", "localStorage.getItem", "localStorage.setItem", "sessionStorage", "indexedDB"]) expect(source).not.toContain(api);
});

test("removes a legacy browser credential without using it to sign in", async () => {
  storage.setItem("deal-xray.account-session", "legacy-secret");
  const fetcher = vi.fn(async () => json({}, 401));
  vi.stubGlobal("fetch", fetcher);
  render(<BuyerAccount />);
  expect(await screen.findByRole("button", { name: "Email me a sign-in link" })).toBeVisible();
  expect(storage.size).toBe(0);
  expect(fetcher).toHaveBeenCalledWith("/api/v1/account", { headers: {} });
});

test("saves each email preference independently and keeps security email required", async () => {
  let preferences = ["DEALER_REPLY", "DRAFT_NEEDS_APPROVAL", "MANDATE_EXPIRING", "APPOINTMENT", "ACCOUNT_SECURITY"]
    .map(type => ({ type, enabled: true, mandatory: type === "ACCOUNT_SECURITY" }));
  const fetcher = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    if (String(input) !== "/api/v1/account/notifications") return json(account());
    if (init?.method === "PUT") {
      const change = JSON.parse(String(init.body));
      preferences = preferences.map(value => value.type === change.type ? { ...value, enabled: change.enabled } : value);
    }
    return json(preferences);
  });
  vi.stubGlobal("fetch", fetcher);
  render(<BuyerAccount />);
  const user = userEvent.setup();
  const security = await screen.findByLabelText("Account security (required)");
  expect(security).toBeChecked(); expect(security).toBeDisabled();
  for (const label of ["Dealer replies", "Drafts needing approval", "Mandate expiry", "Appointments"]) {
    await user.click(screen.getByLabelText(label));
    await waitFor(() => expect(screen.getByLabelText(label)).not.toBeChecked());
    expect(screen.getByLabelText(label)).not.toBeDisabled();
  }
  expect(screen.getByRole("status")).toHaveTextContent("Email preferences saved.");
  expect(fetcher.mock.calls.filter(([, init]) => init?.method === "PUT")).toHaveLength(4);
  cleanup(); render(<BuyerAccount />);
  expect(await screen.findByLabelText("Dealer replies")).not.toBeChecked();
});

test("failed email preference saves retain the saved value and can be retried", async () => {
  let fail = true;
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    if (String(input) !== "/api/v1/account/notifications") return json(account());
    if (init?.method === "PUT" && fail) return json({}, 503);
    return json([{ type: "DEALER_REPLY", enabled: init?.method !== "PUT", mandatory: false }]);
  }));
  render(<BuyerAccount />);
  const user = userEvent.setup();
  await user.click(await screen.findByLabelText("Dealer replies"));
  expect(await screen.findByRole("alert")).toHaveTextContent("could not be saved");
  expect(screen.getByLabelText("Dealer replies")).toBeChecked();
  fail = false;
  await user.click(screen.getByLabelText("Dealer replies"));
  await waitFor(() => expect(screen.getByLabelText("Dealer replies")).not.toBeChecked());
});

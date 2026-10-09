import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { readFileSync } from "node:fs";
import { afterEach, expect, test, vi } from "vitest";

const OPTIONS = { supported: ["US-NC"], consentPolicies: { serviceProcessing: "service-processing.v1" } };

function stubApi() {
  const calls: string[] = [];
  vi.spyOn(globalThis, "fetch").mockImplementation(async input => {
    const url = String(input);
    calls.push(url);
    const body = url.endsWith("/intake-options") ? OPTIONS : [];
    return new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });
  });
  return calls;
}

/** Each address is a fresh page load, so the channel code is read afresh. */
async function freshApp(address: string) {
  window.history.replaceState(null, "", address);
  vi.resetModules();
  return (await import("./App")).default;
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  window.history.replaceState(null, "", "/");
});

const KIT = "Referral partner kit";

test("the kit opens without a case at its stable address and replaces the start screen", async () => {
  const calls = stubApi();
  const App = await freshApp("/?page=referral-partners");
  render(<App />);
  expect(screen.getByRole("heading", { level: 1, name: KIT })).toBeTruthy();
  expect(window.location.search).toBe("?page=referral-partners");
  expect(screen.queryByRole("heading", { name: "Start your Buying Case" })).toBeNull();
  expect(calls.some(url => url.includes("/api/v1/cases"))).toBe(false);
});

test("the page footer links to the kit, and an unknown page shows the usual start screen", async () => {
  stubApi();
  const App = await freshApp("/?page=unknown");
  render(<App />);
  expect(screen.getByRole("heading", { name: "Start your Buying Case" })).toBeTruthy();
  expect(screen.queryByRole("heading", { level: 1, name: KIT })).toBeNull();
  const footer = screen.getByRole("contentinfo");
  expect(within(footer).getByRole("link", { name: KIT }).getAttribute("href")).toBe("?page=referral-partners");
  const terms = footer.textContent!.replace(/\s+/g, " ");
  expect(terms).toContain("otdog is a tool on your side, the Buyer's. We don't work with dealers. We help people.");
  expect(terms).toContain("You send every email from your own email and make every decision");
  expect(terms).toContain("No dealer, lender, or other commercial party pays for placement, leads, or referrals");
  expect(terms).not.toMatch(/gmail|outlook|mailbox/i);
});

test("the kit names its audience, excludes dealers and lenders, and states the footer's terms", async () => {
  const { ReferralPartnerKit } = await import("./ReferralPartnerKit");
  render(<ReferralPartnerKit />);
  const kit = screen.getByRole("article");
  expect(kit.textContent).toContain("consumer educators and independent inspection services");
  expect(kit.textContent).toContain("Dealers, lenders, and parties affiliated with a dealer cannot be referral partners.");
  const terms = within(screen.getByRole("region", { name: "Terms of the arrangement" })).getAllByRole("listitem")
    .map(item => item.textContent!.replace(/\s+/g, " "));
  expect(terms).toContain("Partners are not paid for referrals, and partners do not pay for placement or referrals.");
  expect(terms).toContain("No dealer, lender, or other commercial party pays for placement, leads, or referrals.");
  expect(terms.some(term => term.includes("shown a disclosure of the referral"))).toBe(true);
  expect(terms.some(term => term.includes("No partner relationship affects any market range, rating, finding, or suggested boundary"))).toBe(true);

  // The example is the case view's own panel, inside a figure captioned as an example.
  const disclosure = screen.getByRole("region", { name: "How you reached us" });
  const figure = disclosure.closest("figure")!;
  expect(figure.querySelector("figcaption")!.textContent).toMatch(/^Example only/);
  expect(disclosure.textContent).toContain("This referral does not change the analysis, the report, or the price offered to you.");
});

test("the kit describes the product as help the Buyer uses and states the credo", async () => {
  const { ReferralPartnerKit } = await import("./ReferralPartnerKit");
  render(<ReferralPartnerKit />);
  const text = (name: string) => screen.getByRole("region", { name }).textContent!.replace(/\s+/g, " ");
  expect(text("Who can be a referral partner")).toContain("does not contact dealers for the Buyer.");
  const product = text("What otdog does");
  expect(product).toContain("We don't work with dealers. We help people.");
  expect(product).toContain("find cars, check dealer quotes, draft their own emails to dealers, and check the paperwork");
  expect(product).toContain("The Buyer sends every email from their own email and makes every decision");
  expect(product).not.toMatch(/gmail|outlook|mailbox/i);
});

test.each(["", "-inspector", "Inspector", "inspector_1", "inspector 1", "a".repeat(33)])(
  "the link builder rejects %j and composes no link", async code => {
    const { ReferralPartnerKit } = await import("./ReferralPartnerKit");
    render(<ReferralPartnerKit />);
    const user = userEvent.setup();
    if (code) await user.type(screen.getByLabelText("Channel code"), code);
    await user.click(screen.getByRole("button", { name: "Build links" }));
    expect(screen.getByRole("alert").textContent).toMatch(/not a channel code/);
    expect(screen.getByLabelText("Channel code").getAttribute("aria-invalid")).toBe("true");
    expect(screen.queryByRole("list", { name: /referral links/i })).toBeNull();
  });

test("the link builder composes copyable links in the page, without a request, cookie, or storage", async () => {
  const fetched = vi.spyOn(globalThis, "fetch");
  const stored = vi.spyOn(Storage.prototype, "setItem");
  window.history.replaceState(null, "", "/?page=referral-partners");
  const { ReferralPartnerKit } = await import("./ReferralPartnerKit");
  render(<ReferralPartnerKit />);
  expect(screen.getByRole("region", { name: "Build your referral links" }).textContent)
    .toMatch(/counted only after the operator has configured that code/);

  const user = userEvent.setup();
  // After setup, which installs its own clipboard.
  const copy = vi.fn().mockResolvedValue(undefined);
  Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText: copy } });
  await user.type(screen.getByLabelText("Channel code"), "inspector-1");
  await user.click(screen.getByRole("button", { name: "Build links" }));
  const links = within(screen.getByRole("list", { name: "Referral links for inspector-1" })).getAllByRole("textbox")
    .map(input => (input as HTMLInputElement).value);
  const origin = window.location.origin;
  expect(links).toEqual([
    `${origin}/?channel=inspector-1`,
    `${origin}/?guide=out-the-door-quote&channel=inspector-1`,
    `${origin}/?guide=dealer-fees&channel=inspector-1`,
  ]);
  await user.click(screen.getByRole("button", { name: "Copy Guide: Which dealer fees to ask about link" }));
  expect(copy).toHaveBeenCalledWith(links[2]);
  expect(await screen.findByText("Link copied.")).toBeTruthy();

  expect(fetched).not.toHaveBeenCalled();
  expect(stored).not.toHaveBeenCalled();
  expect(document.cookie).toBe("");
  expect(screen.queryByRole("alert")).toBeNull();
});

test("a guide reached by a partner link starts the case with the partner's code", async () => {
  stubApi();
  const App = await freshApp("/?guide=dealer-fees&channel=inspector-1");
  render(<App />);
  expect(screen.getByRole("heading", { level: 1, name: "Which dealer fees to ask about" })).toBeTruthy();
  expect(window.location.search).toBe("?guide=dealer-fees");
  expect(screen.getByRole("link", { name: "Start a case with your quote" }).getAttribute("href"))
    .toBe("?channel=inspector-1#intake-heading");
});

test("the kit source makes no request, keeps nothing, and avoids prohibited claims", () => {
  const source = readFileSync("src/ReferralPartnerKit.tsx", "utf8").replace(/\/\*\*[\s\S]*?\*\//g, "");
  for (const api of ["fetch(", "localStorage", "sessionStorage", "indexedDB", "document.cookie", "referrer", "<script", "<img", "<iframe", "http"]) {
    expect(source, `${api} has no place in the kit`).not.toContain(api);
  }
  for (const phrase of [/ai negotiator/i, /safe to sign/i, /\bsav(e|es|ed|ing|ings)\b/i, /\badvice\b/i, /guarantee/i,
    /negotiates? for/i, /on (your|the buyer's) behalf/i, /\bour agent\b/i, /autonomous/i, /represents (you|the buyer)/i]) {
    expect(source).not.toMatch(phrase);
  }
});

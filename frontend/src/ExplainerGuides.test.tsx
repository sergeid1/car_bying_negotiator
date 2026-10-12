import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { readFileSync } from "node:fs";
import { afterEach, expect, test, vi } from "vitest";

const OPTIONS = { buyerState: "US-NC", supported: ["US-NC"], consentPolicies: { serviceProcessing: "service-processing.v1" } };

function stubApi() {
  const calls: { url: string; body?: Record<string, unknown> }[] = [];
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    const url = String(input);
    calls.push({ url, body: init?.body ? JSON.parse(String(init.body)) : undefined });
    const body = url.includes("/intake-options") ? OPTIONS
      : url.endsWith("/api/v1/cases") ? { case: { id: "case-1", referralDisclosure: null }, accessToken: "secret" }
      : url.endsWith("/vehicle-evidence") ? { identityConflicts: [], reliableForAnalysis: true }
      : url.endsWith("/document-limits") ? { acceptedMediaTypes: ["image/png"], maxFileBytes: 1, maxPages: 1, maxPixels: 1, minWidth: 1, minHeight: 1 }
      : [];
    return new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });
  });
  return calls;
}

/** The channel code is read once per page load, so each test loads the client afresh. */
async function freshApp(address: string) {
  window.history.replaceState(null, "", address);
  vi.resetModules();
  return (await import("./App")).default;
}

/** Follows a link the way the browser would: a new page load at the link's address. */
async function follow(link: HTMLElement) {
  const target = new URL(link.getAttribute("href")!, window.location.href);
  cleanup();
  return freshApp(target.pathname + target.search + target.hash);
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  window.history.replaceState(null, "", "/");
});

const GUIDES = [
  { slug: "out-the-door-quote", title: "How to read an out-the-door quote", channel: "guide-otd-quote" },
  { slug: "dealer-fees", title: "Which dealer fees to ask about", channel: "guide-dealer-fees" },
];

test("the start screen links to both guides at their stable addresses", async () => {
  stubApi();
  const App = await freshApp("/");
  render(<App />);
  const links = within(screen.getByRole("region", { name: "Guides to reading your quote" })).getAllByRole("link");
  expect(links.map(link => [link.textContent, link.getAttribute("href")])).toEqual(
    GUIDES.map(guide => [guide.title, `?guide=${guide.slug}`]));
});

test.each(GUIDES)("$slug opens without a case and its start action begins intake with $channel", async guide => {
  const calls = stubApi();
  let App = await freshApp(`/?guide=${guide.slug}`);
  render(<App />);

  expect(screen.getByRole("heading", { level: 1, name: guide.title })).toBeTruthy();
  // The guide stays at its shareable address and replaces the start screen; no case is begun.
  expect(window.location.search).toBe(`?guide=${guide.slug}`);
  expect(screen.queryByRole("heading", { name: "Start your Buying Case" })).toBeNull();
  expect(calls.some(call => call.url.endsWith("/api/v1/cases"))).toBe(false);

  const start = screen.getByRole("link", { name: "Start a case with your quote" });
  expect(start.getAttribute("href")).toBe(`?channel=${guide.channel}#intake-heading`);

  App = await follow(start);
  render(<App />);
  expect(window.location.search).toBe("");
  const user = userEvent.setup();
  await user.type(screen.getByLabelText(/your zip code/i), "27601");
  await user.type(screen.getByLabelText(/dealer city/i), "27604");
  await user.selectOptions(screen.getByLabelText(/dealer state/i), "NC");
  await user.type(screen.getByLabelText(/^vin$/i), "1HGCM82633A004352");
  await user.click(screen.getByRole("button", { name: /start my case/i }));
  await waitFor(() => expect(calls.some(call => call.url.endsWith("/vehicle-evidence"))).toBe(true));
  expect(calls.find(call => call.url.endsWith("/api/v1/cases"))!.body!.channel).toBe(guide.channel);
});

test.each(GUIDES)("$slug describes the journey as help the Buyer uses and states the credo", async guide => {
  stubApi();
  const App = await freshApp(`/?guide=${guide.slug}`);
  render(<App />);
  const start = screen.getByRole("region", { name: "Check your own quote" }).textContent!.replace(/\s+/g, " ");
  expect(start).toContain("find cars, check quotes, draft your emails to dealers, and check desk paperwork");
  expect(start).toContain("You send every email from your own email and make every decision");
  expect(start).toContain("We don't work with dealers. We help people.");
});

test("the start screen states the Buyer's side of the deal and the credo", async () => {
  stubApi();
  const App = await freshApp("/");
  render(<App />);
  const hero = screen.getByRole("heading", { level: 1 }).parentElement!.textContent!.replace(/\s+/g, " ");
  expect(hero).toContain("Check your dealer's quote");
  expect(hero).toContain("Check my deal");
  expect(hero).toContain("We never work for dealers");
  expect(hero).not.toMatch(/gmail|outlook|mailbox/i);
});

test("an unknown guide name shows the usual start screen", async () => {
  stubApi();
  const App = await freshApp("/?guide=unknown");
  render(<App />);
  expect(screen.getByRole("heading", { name: "Start your Buying Case" })).toBeTruthy();
  expect(screen.queryByRole("heading", { level: 1, name: /guide|quote|fees/i })).toBeNull();
});

test("the guides fetch nothing, store nothing, and label every amount as an example", async () => {
  for (const guide of GUIDES) {
    const fetched = vi.spyOn(globalThis, "fetch");
    const stored = vi.spyOn(Storage.prototype, "setItem");
    const { ExplainerGuide } = await import("./ExplainerGuides");
    render(<ExplainerGuide slug={guide.slug as "dealer-fees"} />);
    expect(fetched).not.toHaveBeenCalled();
    expect(stored).not.toHaveBeenCalled();
    expect(document.cookie).toBe("");
    const article = screen.getByRole("article");
    for (const amount of article.querySelectorAll("dd")) {
      expect(amount.previousElementSibling?.textContent).toMatch(/\(example\)$/);
    }
    const unlabelled = Array.from(article.querySelectorAll("p, li"))
      .filter(element => /\$|\d/.test(element.textContent ?? ""));
    expect(unlabelled.map(element => element.textContent)).toEqual([]);
    cleanup();
    vi.restoreAllMocks();
  }
  const source = readFileSync("src/ExplainerGuides.tsx", "utf8").replace(/\/\*\*[\s\S]*?\*\//g, "");
  for (const api of ["fetch(", "localStorage", "sessionStorage", "indexedDB", "document.cookie", "referrer", "navigator.", "<script", "<img", "<iframe", "http"]) {
    expect(source, `${api} has no place in the guides`).not.toContain(api);
  }
  for (const phrase of [/ai negotiator/i, /safe to sign/i, /\bsav(e|es|ed|ing|ings)\b/i, /negotiates? for/i, /on (your|the buyer's) behalf/i, /\bour agent\b/i, /autonomous/i, /represents (you|the buyer)/i]) {
    expect(source).not.toMatch(phrase);
  }
});

import { cleanup, render, screen, within } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import { HowOtdogMakesMoney } from "./HowOtdogMakesMoney";

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

const TITLE = "How otdog makes money";

test("the page opens without a case at its stable address and replaces the start screen", async () => {
  const calls = stubApi();
  const App = await freshApp("/?page=how-otdog-makes-money");
  render(<App />);
  expect(screen.getByRole("heading", { level: 1, name: TITLE })).toBeTruthy();
  expect(screen.queryByRole("heading", { name: "Start your Buying Case" })).toBeNull();
  expect(calls.some(url => url.includes("/api/v1/cases"))).toBe(false);
});

test("the page says who may pay, who never pays, and what pay never changes, without naming amounts", () => {
  render(<HowOtdogMakesMoney />);
  const page = screen.getByRole("article");
  const text = page.textContent!.replace(/\s+/g, " ");
  expect(text).toContain("Today otdog earns no money from anyone.");
  expect(text).toContain("The insurance and lending companies you see there, or our partner, may pay otdog.");
  expect(text).toContain("You never pay otdog anything");
  expect(text).toContain("We don't work with dealers. We help people.");
  expect(text).toContain("We never sell your information.");
  const rules = within(screen.getByRole("region", { name: "What pay never changes" })).getAllByRole("listitem")
    .map(item => item.textContent!.replace(/\s+/g, " "));
  expect(rules.some(rule => rule.includes("does not move up the list"))).toBe(true);
  expect(rules.some(rule => rule.includes("If the dealer's financing costs less"))).toBe(true);
  expect(text).not.toMatch(/\$|\d+%|per quote|per policy/);
});

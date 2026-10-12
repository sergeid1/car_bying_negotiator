import { act, cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import { HowItWorks, HowItWorksCard, sampleFigures, STEPS, type WalkthroughStep } from "./HowItWorks";

/** The shape `GET /api/v1/sample-report` serves today: the synthetic case's economics. */
const SAMPLE = { label: "EXAMPLE", source: "SYNTHETIC_CASE", redaction: { method: "FIELD_CATEGORIES", version: "sample-redaction.v2", removed: ["VIN"] },
  report: { analysis: {
    reviewStatus: "REVIEW_REQUIRED", dealRating: "FAIR_NEGOTIATE", nextAction: "Ask the dealer to itemize the fees.",
    topReasons: ["Dealer fees are above the recorded range.", "Add-ons are listed separately."],
    economics: {
      sellingPrice: { minorUnits: 2500000, currency: "USD" }, dealerFees: { minorUnits: 79900, currency: "USD" },
      addOns: { minorUnits: 50000, currency: "USD" }, taxes: { minorUnits: 194600, currency: "USD" },
      governmentCharges: { minorUnits: 23000, currency: "USD" }, outTheDoorTotal: { minorUnits: 2847500, currency: "USD" },
    },
  } } };

function stubApi(sample: unknown = SAMPLE, ok = true) {
  const calls: string[] = [];
  vi.spyOn(globalThis, "fetch").mockImplementation(async input => {
    const url = String(input);
    calls.push(url);
    const body = url.endsWith("/sample-report") ? sample : url.endsWith("/intake-options") ? { supported: ["US-NC"], consentPolicies: {} } : [];
    return new Response(JSON.stringify(body), { status: ok ? 200 : 503, headers: { "Content-Type": "application/json" } });
  });
  return calls;
}

async function freshApp(address: string) {
  window.history.replaceState(null, "", address);
  vi.resetModules();
  return (await import("./App")).default;
}

/** A wide screen with motion allowed, and an IntersectionObserver the test drives by hand. */
function memoryStorage() {
  const values = new Map<string, string>();
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => { values.set(key, value); },
    removeItem: (key: string) => { values.delete(key); },
  };
}

/** Sets a browser API on jsdom's window for one test. */
function stubWindow(name: string, value: unknown) {
  Object.defineProperty(window, name, { value, configurable: true, writable: true });
}

function pinnedBrowser({ reducedMotion = false, saveData = false } = {}) {
  const observers: { callback: IntersectionObserverCallback; options?: IntersectionObserverInit; targets: Element[] }[] = [];
  stubWindow("IntersectionObserver", class {
    targets: Element[] = [];
    constructor(public callback: IntersectionObserverCallback, public options?: IntersectionObserverInit) { observers.push(this); }
    observe(target: Element) { this.targets.push(target); }
    disconnect() {}
  });
  stubWindow("matchMedia", (query: string) => ({
    matches: query === "(prefers-reduced-motion: reduce)" ? reducedMotion : !reducedMotion,
    addEventListener() {}, removeEventListener() {},
  }));
  Object.defineProperty(navigator, "connection", { value: { saveData }, configurable: true });
  return observers;
}

beforeEach(() => stubWindow("localStorage", memoryStorage()));

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  for (const name of ["IntersectionObserver", "matchMedia", "localStorage"]) Reflect.deleteProperty(window, name);
  Reflect.deleteProperty(navigator, "connection");
  window.history.replaceState(null, "", "/");
});

test("the walkthrough opens without a case at its own address and title, and replaces the start screen", async () => {
  stubApi();
  const App = await freshApp("/?page=how-it-works");
  render(<App />);
  expect(screen.getByRole("heading", { level: 1, name: "Buying a car with otdog, step by step" })).toBeTruthy();
  expect(document.title).toBe("How it works · otdog");
  expect(screen.queryByRole("heading", { name: "Start your Buying Case" })).toBeNull();
  expect(screen.queryByRole("heading", { name: "New here? See how it works" })).toBeNull();
  const next = screen.getByRole("navigation", { name: "Get started" });
  expect(within(next).getByRole("link", { name: "Start with your quote" })).toHaveAttribute("href", "./");
  expect(within(next).getByRole("link", { name: "See a sample report" })).toHaveAttribute("href", "./#sample-report");
});

test("the steps are an ordered list with described scenes and labelled example screens from the sample report", async () => {
  const calls = stubApi();
  const { container } = render(<HowItWorks />);
  const steps = container.querySelectorAll("ol.walkthrough > li");
  expect(steps).toHaveLength(7);
  expect(Array.from(steps, step => step.querySelector("h2")?.textContent)).toEqual(STEPS.map(step => step.title));
  for (const step of steps) {
    expect(within(step as HTMLElement).getByRole("img").getAttribute("aria-label")).toMatch(/^Placeholder scene: .+\.$/);
    expect(within(step as HTMLElement).getByRole("figure")).toHaveTextContent("Example screen from a synthetic case");
  }
  expect(calls).toEqual(["/api/v1/sample-report"]);
  const report = container.querySelector<HTMLElement>("#how-step-report")!;
  expect(await within(report).findByText("$28,475.00")).toBeTruthy();
  expect(screen.getByText("Dealer fees are above the recorded range.")).toBeTruthy();
  expect(screen.getByText(/dealer fees of \$799\.00 and add-ons of \$500\.00/)).toBeTruthy();
  // Each step is a keyboard target from the step list.
  const index = screen.getByRole("navigation", { name: "Steps" });
  expect(within(index).getAllByRole("link").map(link => link.getAttribute("href"))).toEqual(STEPS.map(step => `#how-step-${step.id}`));
  expect(steps[0]).toHaveAttribute("tabindex", "-1");
});

test("the walkthrough says what the Buyer does and never that otdog negotiates or sends", () => {
  stubApi();
  const { container } = render(<HowItWorks />);
  const copy = container.textContent ?? "";
  expect(copy).toMatch(/you send it from your own email/);
  expect(copy).toMatch(/You edit it and send it yourself/);
  expect(copy).not.toMatch(/negotiat|on your behalf|for you\b/i);
});

test("an unavailable sample report leaves the steps readable", async () => {
  stubApi({}, false);
  render(<HowItWorks />);
  expect(await screen.findByRole("note")).toHaveTextContent("The example figures are unavailable right now.");
  expect(screen.getAllByText("Example figures unavailable.")).toHaveLength(7);
});

test("sample figures read both the economics and the material-amount report shapes", () => {
  expect(sampleFigures(SAMPLE)).toMatchObject({ total: 2847500, sellingPrice: 2500000, dealerFees: 79900, currency: "USD" });
  expect(sampleFigures({ report: { analysis: { topReasons: ["A", null], nextAction: "Ask", materialAmounts: [
    { label: "Out-the-door total", pointer: "/result/outTheDoorTotal", minorUnits: 2850000, currency: "USD" },
    { label: "Selling price", pointer: "/result/sellingPrice", minorUnits: 2500000, currency: "USD" },
  ] } } })).toMatchObject({ total: 2850000, sellingPrice: 2500000, dealerFees: null, reasons: ["A"] });
  expect(sampleFigures({})).toBeUndefined();
});

test("with motion on a wide screen the scene pins and swaps to the step whose caption reaches the middle", async () => {
  stubApi();
  const observers = pinnedBrowser();
  const { container } = render(<HowItWorks />);
  expect(container.querySelector("ol.walkthrough")).toHaveClass("walkthrough-pinned");
  const middle = observers.find(observer => observer.options?.rootMargin === "-50% 0px -50% 0px")!;
  const steps = container.querySelectorAll("ol.walkthrough > li");
  expect(steps[0]).toHaveClass("is-active");
  act(() => middle.callback([{ isIntersecting: true, target: steps[3] } as unknown as IntersectionObserverEntry], {} as IntersectionObserver));
  expect(steps[3]).toHaveClass("is-active");
  expect(steps[0]).not.toHaveClass("is-active");
  // Keyboard focus brings its step on stage.
  await userEvent.setup().click(within(screen.getByRole("navigation", { name: "Steps" })).getByRole("link", { name: STEPS[5].title }));
  act(() => (steps[5] as HTMLElement).focus());
  expect(steps[5]).toHaveClass("is-active");
});

const CLIPPED: WalkthroughStep[] = STEPS.map((step, index) => ({ ...step, media: { clip: `/clips/step-${index + 1}.mp4`, poster: `/clips/step-${index + 1}.webp` } }));

test("clips load only as their step approaches", () => {
  stubApi();
  const observers = pinnedBrowser();
  const { container } = render(<HowItWorks steps={CLIPPED} />);
  expect(container.querySelectorAll("video")).toHaveLength(1);
  expect(container.querySelector("video")).toHaveAttribute("src", "/clips/step-1.mp4");
  expect(container.querySelectorAll("img")).toHaveLength(6);
  const approaching = observers.find(observer => observer.options?.rootMargin === "100% 0px")!;
  const steps = container.querySelectorAll("ol.walkthrough > li");
  act(() => approaching.callback([{ isIntersecting: true, target: steps[1] } as unknown as IntersectionObserverEntry], {} as IntersectionObserver));
  expect(container.querySelectorAll("video")).toHaveLength(2);
  expect(screen.getAllByRole("img")[0]).toHaveAccessibleName(STEPS[0].sceneAlt);
});

test.each([{ reducedMotion: true }, { saveData: true }])("no clip is downloaded with %j; poster frames show instead", setting => {
  stubApi();
  pinnedBrowser(setting);
  const { container } = render(<HowItWorks steps={CLIPPED} />);
  expect(container.querySelectorAll("video")).toHaveLength(0);
  expect(Array.from(container.querySelectorAll("img"), img => img.getAttribute("src"))).toEqual(CLIPPED.map(step => step.media!.poster));
  if (setting.reducedMotion) expect(container.querySelector("ol.walkthrough")).not.toHaveClass("walkthrough-pinned");
});

test("the start screen's card links to the walkthrough and stays dismissed", async () => {
  stubApi();
  const App = await freshApp("/");
  const { unmount } = render(<App />);
  const card = screen.getByRole("region", { name: "New here? See how it works" });
  expect(within(card).getByRole("link", { name: "See how it works" })).toHaveAttribute("href", "?page=how-it-works");
  expect(screen.getByRole("heading", { name: "Start your Buying Case" })).toBeTruthy();
  await userEvent.setup().click(within(card).getByRole("button", { name: "Dismiss the how it works card" }));
  expect(screen.queryByRole("region", { name: "New here? See how it works" })).toBeNull();
  unmount();
  render(<App />);
  expect(screen.queryByRole("region", { name: "New here? See how it works" })).toBeNull();
});

test("without browser storage the card can still be dismissed and returns next time", async () => {
  stubWindow("localStorage", { getItem() { throw new Error("blocked"); }, setItem() { throw new Error("blocked"); } });
  const { unmount } = render(<HowItWorksCard />);
  await userEvent.setup().click(screen.getByRole("button", { name: "Dismiss the how it works card" }));
  expect(screen.queryByRole("region")).toBeNull();
  unmount();
  render(<HowItWorksCard />);
  expect(screen.getByRole("region", { name: "New here? See how it works" })).toBeTruthy();
});

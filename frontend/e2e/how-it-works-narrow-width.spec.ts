import AxeBuilder from "@axe-core/playwright";
import { expect, Page, test } from "@playwright/test";

const REPORT = { version: 1, asOf: "2026-09-30", status: "COMPLETE", analysis: {
  reviewLabel: "Review required", dealRating: "FAIR", ratingBasis: "Selling price comparison", nextAction: "Ask the dealer to itemize the fees.",
  topReasons: ["The documentation fee is above the recorded range.", "Add-ons are listed apart from the selling price."],
  marketAvailability: "AVAILABLE", cashAvailability: "AVAILABLE",
  economics: {
    sellingPrice: { minorUnits: 2500000, currency: "USD" }, dealerFees: { minorUnits: 79900, currency: "USD" },
    addOns: { minorUnits: 50000, currency: "USD" }, taxes: { minorUnits: 194600, currency: "USD" },
    governmentCharges: { minorUnits: 23000, currency: "USD" }, outTheDoorTotal: { minorUnits: 2847500, currency: "USD" },
  },
  materialAmounts: [{ label: "Out-the-door total", minorUnits: 2847500, currency: "USD", precision: "EXACT", source: "cashEconomics",
    pointer: "/result/outTheDoorTotal", documentSource: null, sourceNote: null }],
  policyVersions: {}, limitations: ["Financial consistency only."],
}, evidence: {}, reproduction: {} };

const SAMPLE = { label: "EXAMPLE", source: "SYNTHETIC_CASE",
  redaction: { method: "FIELD_CATEGORIES", version: "sample-redaction.v2", removed: ["VIN"] }, report: REPORT };

async function api(page: Page) {
  const requested: string[] = [];
  page.on("request", request => requested.push(new URL(request.url()).pathname));
  await page.route("**/api/v1/**", route => {
    const path = new URL(route.request().url()).pathname;
    if (path === "/api/v1/sample-report") return route.fulfill({ json: SAMPLE });
    if (path === "/api/v1/intake-options") return route.fulfill({ json: { supported: ["US-NC"], consentPolicies: {} } });
    if (path === "/api/v1/account") return route.fulfill({ status: 401, json: {} });
    return route.fulfill({ status: 404, json: {} });
  });
  return requested;
}

async function expectNoHorizontalOverflow(page: Page) {
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
}

async function expectNoAxeViolations(page: Page, selector: string) {
  const results = await new AxeBuilder({ page }).include(selector)
    .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"]).analyze();
  expect(results.violations).toEqual([]);
}

const steps = (page: Page) => page.locator("ol.walkthrough > li");

test("at 360 px the walkthrough is a readable ordered list that fits without horizontal scrolling", async ({ page }) => {
  const requested = await api(page);
  await page.goto("/?page=how-it-works");
  await expect(page.getByRole("heading", { level: 1, name: "Buying a car with otdog, step by step" })).toBeVisible();
  await expect(page).toHaveTitle("How it works · otdog");
  await expect(steps(page)).toHaveCount(7);
  await expect(page.locator("ol.walkthrough")).not.toHaveClass(/walkthrough-pinned/);
  await expect(page.locator("#how-step-report")).toContainText("$28,475.00");
  // Every scene and example screen is on screen in order, none hidden behind another.
  for (const step of await steps(page).all()) {
    await step.scrollIntoViewIfNeeded();
    await expect(step.getByRole("img")).toBeVisible();
    await expect(step.getByRole("figure")).toBeVisible();
  }
  await expectNoHorizontalOverflow(page);
  await expectNoAxeViolations(page, "article.how-it-works");
  expect(requested.filter(path => path.endsWith(".mp4") || path.endsWith(".webp"))).toEqual([]);
});

test("with reduced motion on a wide screen nothing pins or fades", async ({ page }) => {
  await api(page);
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.goto("/?page=how-it-works");
  await expect(steps(page)).toHaveCount(7);
  await expect(page.locator("ol.walkthrough")).not.toHaveClass(/walkthrough-pinned/);
  const frames = page.locator(".walkthrough-frame");
  for (const frame of await frames.all()) {
    expect(await frame.evaluate(element => [getComputedStyle(element).position, getComputedStyle(element).opacity])).toEqual(["static", "1"]);
  }
});

test("on a wide screen the scene stays pinned and swaps as each caption reaches the middle", async ({ page }) => {
  await api(page);
  await page.emulateMedia({ reducedMotion: "no-preference" });
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.goto("/?page=how-it-works");
  await expect(page.locator("ol.walkthrough")).toHaveClass(/walkthrough-pinned/);
  for (const index of [2, 4, 6]) {
    await steps(page).nth(index).locator(".walkthrough-caption").evaluate(element => {
      const box = element.getBoundingClientRect();
      window.scrollBy(0, box.top + box.height / 2 - window.innerHeight / 2);
    });
    await expect(steps(page).nth(index)).toHaveClass(/is-active/);
    const frame = steps(page).nth(index).locator(".walkthrough-frame");
    await expect(frame).toHaveCSS("opacity", "1");
    const box = (await frame.boundingBox())!;
    expect(box.y).toBeGreaterThanOrEqual(0);
    expect(box.y + box.height).toBeLessThanOrEqual(800);
    await expect(steps(page).nth(index - 1).locator(".walkthrough-frame")).toHaveCSS("opacity", "0");
  }
  await expectNoHorizontalOverflow(page);
  await expectNoAxeViolations(page, "article.how-it-works");
});

test("the start screen invites a new visitor without covering the start form, and remembers a dismissal", async ({ page }) => {
  await api(page);
  await page.goto("/");
  const card = page.getByRole("region", { name: "New here? See how it works" });
  await expect(card).toBeVisible();
  await expect(page.getByRole("heading", { name: "Start your Buying Case" })).toBeVisible();
  await expectNoHorizontalOverflow(page);
  await expectNoAxeViolations(page, "section.how-it-works-card");
  await card.getByRole("button", { name: "Dismiss the how it works card" }).click();
  await expect(card).toHaveCount(0);
  await page.reload();
  await expect(page.getByRole("heading", { name: "Start your Buying Case" })).toBeVisible();
  await expect(card).toHaveCount(0);
});

test("the walkthrough's closing actions reach the start form and the opened example report", async ({ page }) => {
  await api(page);
  await page.goto("/");
  await page.getByRole("region", { name: "New here? See how it works" }).getByRole("link", { name: "See how it works" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Buying a car with otdog, step by step" })).toBeVisible();
  await page.getByRole("link", { name: "See a sample report" }).click();
  await expect(page.getByRole("note", { name: "About this example" })).toBeVisible();
  await page.goto("/?page=how-it-works");
  await page.getByRole("link", { name: "Start with your quote" }).click();
  await expect(page.getByRole("heading", { name: "Start your Buying Case" })).toBeVisible();
});

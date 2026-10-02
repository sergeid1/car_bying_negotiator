import AxeBuilder from "@axe-core/playwright";
import { expect, Page, test } from "@playwright/test";

const OPTIONS = {
  supported: ["US-NC"],
  consentPolicies: {
    serviceProcessing: "service-processing.v1",
    humanReview: "human-review.v1",
    anonymizedResearch: "anonymized-research.v1",
  },
};

async function openLanding(page: Page) {
  await page.route("**/api/v1/**", route => route.fulfill({ status: 204, body: "" }));
  await page.route("**/api/v1/intake-options", route => route.fulfill({ json: OPTIONS }));
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Start your Buying Case" })).toBeVisible();
}

const background = (page: Page) => page.evaluate(() => getComputedStyle(document.body).backgroundColor);

test("a pinned theme survives a reload and is applied before the app renders", async ({ page }) => {
  await openLanding(page);
  await page.getByRole("button", { name: /^Appearance:/ }).click();
  await page.getByRole("menuitemradio", { name: "Dark" }).click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  // The theme cross-fades; read the colour once it settles.
  await page.waitForTimeout(600);
  const dark = await background(page);

  // Record the attribute at the first moment the document exists, before any module script runs.
  await page.addInitScript(() => {
    document.addEventListener("DOMContentLoaded", () => {
      (window as unknown as { themeAtLoad: string | null }).themeAtLoad = document.documentElement.getAttribute("data-theme");
    });
  });
  await page.reload();
  await expect(page.getByRole("heading", { name: "Start your Buying Case" })).toBeVisible();
  expect(await page.evaluate(() => (window as unknown as { themeAtLoad: string | null }).themeAtLoad)).toBe("dark");
  expect(await background(page)).toBe(dark);
  await expect(page.getByRole("button", { name: "Appearance: Dark" })).toBeVisible();

  await page.getByRole("button", { name: "Appearance: Dark" }).click();
  await page.getByRole("menuitemradio", { name: "Light" }).click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
  await page.waitForTimeout(600);
  expect(await background(page)).not.toBe(dark);
});

test("the open appearance menu fits at 360 px and has no AA violations, including colour contrast", async ({ page }) => {
  await openLanding(page);
  await page.getByRole("button", { name: /^Appearance:/ }).click();
  const menu = page.getByRole("menu", { name: "Appearance" });
  await expect(menu).toBeVisible();
  const box = await menu.boundingBox();
  expect(box!.x).toBeGreaterThanOrEqual(0);
  expect(box!.x + box!.width).toBeLessThanOrEqual(360);

  const results = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"]).analyze();
  expect(results.violations.map(violation => ({ id: violation.id, nodes: violation.nodes.map(node => node.html) }))).toEqual([]);
});

test("each pinned theme keeps the landing page within 360 px and free of contrast violations", async ({ page }) => {
  for (const theme of ["Light", "Dark"]) {
    await openLanding(page);
    await page.getByRole("button", { name: /^Appearance:/ }).click();
    await page.getByRole("menuitemradio", { name: theme }).click();
    // Let the entrance animation and theme cross-fade settle before measuring colours.
    await page.waitForTimeout(900);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow).toBeLessThanOrEqual(0);
    const results = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"]).analyze();
    expect(results.violations.map(violation => ({ theme, id: violation.id, nodes: violation.nodes.map(node => node.html) }))).toEqual([]);
  }
});

test("the vehicle choice works as a segmented control from the keyboard", async ({ page }) => {
  await openLanding(page);
  const vin = page.getByRole("radio", { name: "I have the VIN" });
  const details = page.getByRole("radio", { name: "Enter the vehicle details" });
  await expect(vin).toBeChecked();
  await vin.focus();
  await page.keyboard.press("ArrowRight");
  await expect(details).toBeChecked();
  await expect(page.getByLabel("Make")).toBeVisible();
  await page.getByText("I have the VIN").click();
  await expect(vin).toBeChecked();
  await expect(page.getByLabel("VIN", { exact: true })).toBeVisible();
});

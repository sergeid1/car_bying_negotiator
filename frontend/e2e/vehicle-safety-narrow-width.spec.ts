import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";
import { caseFlowRecord } from "../src/CaseFlow.fixture";
import { vehicleSafetyFixture } from "../src/VehicleSafety.fixture";

test("model recalls and all rating variants fit at 360 px in both themes", async ({ page }) => {
  await page.route("**/api/v1/**", route => {
    const path = new URL(route.request().url()).pathname;
    const body = path.endsWith("/vehicle-safety") ? vehicleSafetyFixture : caseFlowRecord("NEW", path);
    return body === undefined ? route.fulfill({ status: 404, json: {} }) : route.fulfill({ json: body });
  });
  await page.goto("/#case=case-1&access=secret");
  const panel = page.locator("details.vehicle-safety");
  await panel.locator("summary").click();
  await expect(panel.getByText("Accord FWD")).toBeVisible();
  await expect(panel.getByText("Accord AWD")).toBeVisible();
  await expect(panel.getByText(/Has campaign 21V001000 been repaired/)).toBeVisible();
  await expect(panel.getByText("1HGCM82633A004352")).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
  const results = await new AxeBuilder({ page }).include("details.vehicle-safety")
    .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"]).analyze();
  expect(results.violations).toEqual([]);
});

test("the final-check action stays on the first screen with the model lookup disclosure", async ({ page }) => {
  await page.route("**/api/v1/**", route => {
    const path = new URL(route.request().url()).pathname;
    const body = path.endsWith("/vehicle-safety") ? vehicleSafetyFixture : caseFlowRecord("DESK_COMPARED", path);
    return body === undefined ? route.fulfill({ status: 404, json: {} }) : route.fulfill({ json: body });
  });
  await page.goto("/#case=case-1&access=secret");
  const button = page.getByRole("button", { name: "See each difference" });
  await expect(button).toBeInViewport();
});

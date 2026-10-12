import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";
import { CASE_FLOW_CURRENT, CASE_FLOW_STAGES, caseFlowRecord, type CaseFlowStage } from "../src/CaseFlow.fixture";

/** What the Buyer does first on each stage's current step. */
function mainAction(page: Page, stage: CaseFlowStage) {
  if (stage === "NEW") return page.getByRole("heading", { name: "Request an itemized quote" });
  if (stage === "QUOTE" || stage === "VERIFIED_OFFER") return page.getByRole("heading", { name: "Check the terms against the paperwork" });
  if (stage === "DESK_COMPARED") return page.getByRole("button", { name: "See each difference" });
  return page.getByRole("heading", { name: "Desk Check" });
}

async function openCase(page: Page, stage: CaseFlowStage) {
  await page.route("**/api/v1/**", route => {
    const body = caseFlowRecord(stage, new URL(route.request().url()).pathname);
    return body === undefined ? route.fulfill({ status: 404, json: {} }) : route.fulfill({ json: body });
  });
  await page.goto("/#case=case-1&access=secret");
  const progress = page.getByRole("navigation", { name: "Case progress" });
  await expect(progress.getByRole("button", { name: new RegExp(CASE_FLOW_CURRENT[stage]) })).toHaveAttribute("aria-current", "step");
  return progress;
}

// The narrow and narrow-dark projects run each stage in both themes, at 360 px and again at 1440 px.
for (const stage of CASE_FLOW_STAGES) {
  for (const width of [360, 1440]) {
    test(`a case at stage ${stage} shows the car, the progress bar and its next action on the first screen at ${width} px`, async ({ page }) => {
      await page.setViewportSize({ width, height: width === 360 ? 780 : 900 });
      const progress = await openCase(page, stage);
      await expect(page.locator(".hero")).toHaveCount(0);
      await expect(page.getByRole("heading", { level: 1, name: "2021 Honda Accord EX-L" })).toBeInViewport();
      await expect(progress).toBeInViewport();
      await expect(mainAction(page, stage)).toBeInViewport();
      // Let the theme cross-fade and entrance animation settle before measuring colours and layout.
      await page.waitForTimeout(600);
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
      for (const button of await page.locator(".case-flow-header button, .case-progress button, .case-step-head button").all())
        if (await button.isVisible()) expect((await button.boundingBox())!.height).toBeGreaterThanOrEqual(44);
      const results = await new AxeBuilder({ page }).include(".case-flow")
        .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"]).analyze();
      expect(results.violations.map(violation => ({ id: violation.id, nodes: violation.nodes.map(node => node.html) }))).toEqual([]);
    });
  }
}

test("the progress bar moves between steps by keyboard at 360 px", async ({ page }) => {
  const progress = await openCase(page, "NEW");
  const final = progress.getByRole("button", { name: /Final check/ });
  await final.focus();
  await page.keyboard.press("Enter");
  await expect(final).toHaveAttribute("aria-expanded", "true");
  await expect(page.getByText("Unlocks when the dealer's paperwork is read.")).toBeVisible();
  await expect(page.locator("#case-step-quotes-body")).toBeHidden();
});

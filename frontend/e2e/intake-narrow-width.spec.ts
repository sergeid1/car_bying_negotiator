import { AxeBuilder, expect, Page, test } from "./fixtures";

const OPTIONS = {
  supported: ["US-NC"],
  consentPolicies: {
    serviceProcessing: "service-processing.v1",
    humanReview: "human-review.v1",
    anonymizedResearch: "anonymized-research.v1",
  },
};

/** The layout gate needs no real service, only the shape the intake form renders from. */
async function stubIntakeApi(page: Page) {
  await page.route("**/api/v1/intake-options", route =>
    route.fulfill({ json: OPTIONS }),
  );
  await page.route("**/api/v1/**", route => route.fulfill({ status: 204, body: "" }));
}

test.beforeEach(async ({ page }) => {
  await stubIntakeApi(page);
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Start your Buying Case" })).toBeVisible();
});

test("the intake flow does not scroll horizontally at 360 px", async ({ page }) => {
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
});

test("intake fields stack in a single column at 360 px", async ({ page }) => {
  const form = page.locator("form").first();
  const boxes = await form.locator(":scope > label, :scope > fieldset, :scope > button").evaluateAll(
    elements => elements.map(element => element.getBoundingClientRect()).map(box => ({
      left: Math.round(box.left),
      top: Math.round(box.top),
      bottom: Math.round(box.bottom),
    })),
  );

  expect(boxes.length).toBeGreaterThan(4);
  // A second column would put two controls at different x positions on the same row.
  for (let index = 1; index < boxes.length; index += 1) {
    expect(boxes[index].top).toBeGreaterThanOrEqual(boxes[index - 1].bottom - 1);
  }
});

test("every intake control is labelled and shows a visible focus indicator", async ({ page }) => {
  await page.getByText("Add more (optional)", { exact: true }).click();
  const controls = page.locator("main form input:not([type=radio]):not([type=checkbox]), main form select");
  const count = await controls.count();
  expect(count).toBeGreaterThan(3);

  for (let index = 0; index < count; index += 1) {
    const control = controls.nth(index);
    await expect(control).toHaveAccessibleName(/\S/);
    await control.focus();
    const focusRing = await control.evaluate(element => {
      const style = getComputedStyle(element);
      return { outlineWidth: style.outlineWidth, outlineStyle: style.outlineStyle, boxShadow: style.boxShadow };
    });
    const outlined = focusRing.outlineStyle !== "none" && parseFloat(focusRing.outlineWidth) > 0;
    expect(outlined || focusRing.boxShadow !== "none").toBe(true);
  }
});

test("the rendered intake page has no AA violations, including colour contrast", async ({ page }) => {
  const results = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
    .analyze();

  expect(results.violations.map(violation => ({
    id: violation.id,
    nodes: violation.nodes.map(node => node.html),
  }))).toEqual([]);
});

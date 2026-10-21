import { AxeBuilder, expect, Page, test } from "./fixtures";

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

test("the footer reaches how otdog makes money, which fits 360 px with no automated AA violations", async ({ page }) => {
  await page.route("**/api/v1/**", route => route.fulfill({ status: 204, body: "" }));
  await page.goto("/");
  await page.getByRole("contentinfo").getByRole("link", { name: "How otdog makes money" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "How otdog makes money" })).toBeVisible();
  expect(new URL(page.url()).search).toBe("?page=how-otdog-makes-money");
  await expectNoHorizontalOverflow(page);
  const results = await new AxeBuilder({ page }).include("article.how-otdog-makes-money")
    .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"]).analyze();
  expect(results.violations).toEqual([]);
});

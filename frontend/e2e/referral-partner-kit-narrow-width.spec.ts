import AxeBuilder from "@axe-core/playwright";
import { expect, Page, test } from "@playwright/test";

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

async function expectNoAxeViolations(page: Page) {
  const results = await new AxeBuilder({ page })
    .include("article.partner-kit")
    .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
    .analyze();
  expect(results.violations).toEqual([]);
}

test("the footer reaches the referral partner kit at 360 px", async ({ page }) => {
  await page.route("**/api/v1/**", route => route.fulfill({ status: 204, body: "" }));
  await page.goto("/");
  await page.getByRole("contentinfo").getByRole("link", { name: "Referral partner kit" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Referral partner kit" })).toBeVisible();
  expect(new URL(page.url()).search).toBe("?page=referral-partners");
});

test("the kit and its link builder fit 360 px without horizontal scrolling or requests", async ({ page, baseURL }) => {
  const requested: string[] = [];
  page.on("request", request => requested.push(request.url()));
  await page.goto("/?page=referral-partners");
  await expect(page.getByRole("heading", { level: 1, name: "Referral partner kit" })).toBeVisible();
  await expect(page.getByRole("region", { name: "How you reached us" })).toBeVisible();
  await expectNoHorizontalOverflow(page);
  await expectNoAxeViolations(page);
  const loaded = requested.length;

  await page.getByLabel("Channel code").fill("Not a code");
  await page.getByRole("button", { name: "Build links" }).click();
  await expect(page.getByRole("alert")).toContainText("not a channel code");
  await expectNoHorizontalOverflow(page);

  const code = "independent-inspections-2026-nc";
  await page.getByLabel("Channel code").fill(code);
  await page.getByRole("button", { name: "Build links" }).click();
  const links = page.getByRole("list", { name: `Referral links for ${code}` }).getByRole("textbox");
  await expect(links).toHaveCount(3);
  const origin = new URL(baseURL!).origin;
  expect(await links.evaluateAll(inputs => inputs.map(input => (input as HTMLInputElement).value))).toEqual([
    `${origin}/?channel=${code}`,
    `${origin}/?guide=out-the-door-quote&channel=${code}`,
    `${origin}/?guide=dealer-fees&channel=${code}`,
  ]);
  await expectNoHorizontalOverflow(page);
  await expectNoAxeViolations(page);

  // Building links is entirely in the page: no request, cookie, or storage.
  expect(requested.slice(loaded)).toEqual([]);
  expect(await page.context().cookies()).toEqual([]);
  expect(await page.evaluate(() => localStorage.length + sessionStorage.length)).toBe(0);
});

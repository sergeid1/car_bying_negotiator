import AxeBuilder from "@axe-core/playwright";
import { expect, Page, test } from "@playwright/test";

const ANSWER = { kind: "ANSWER", message: null, suggestions: [], paragraphs: [
  { text: "A doc fee is what the dealer charges to prepare the sale's paperwork. It is a dealer charge, not a government one.",
    citations: [{ articleId: "dealer-fees", title: "Which dealer fees to ask about", link: "?guide=dealer-fees" }] },
  { text: "You can ask the dealer to show it as its own line on the out-the-door quote, next to the taxes and title fees.",
    citations: [{ articleId: "doc-fee", title: "Doc fees and what they cover", link: null },
      { articleId: "out-the-door-quote", title: "How to read an out-the-door quote", link: "?guide=out-the-door-quote" }] },
] };
const FALLBACK = { kind: "FALLBACK", paragraphs: [], message: "I can't answer that one well. These guides may help.",
  suggestions: [{ articleId: "out-the-door-quote", title: "How to read an out-the-door quote", link: "?guide=out-the-door-quote" }] };

async function api(page: Page, available = true) {
  const asked: unknown[] = [];
  const replies = [ANSWER, FALLBACK];
  await page.route("**/api/v1/**", route => {
    const path = new URL(route.request().url()).pathname;
    if (path === "/api/v1/toto") return route.fulfill({ json: { available } });
    if (path === "/api/v1/toto/answers") {
      asked.push(route.request().postDataJSON());
      const reply = replies.shift();
      return reply ? route.fulfill({ json: reply })
        : route.fulfill({ status: 429, json: { code: "TOTO_RESTING", detail: "Toto is resting until tomorrow. The guides are still here." } });
    }
    if (path === "/api/v1/intake-options") return route.fulfill({ json: { supported: ["US-NC"], consentPolicies: {} } });
    if (path === "/api/v1/account") return route.fulfill({ status: 401, json: {} });
    return route.fulfill({ status: 404, json: {} });
  });
  return asked;
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

for (const address of ["/", "/?guide=dealer-fees"]) {
  test(`at 360 px Ask Toto on ${address} opens a full-width dialog that fits without horizontal scrolling`, async ({ page }) => {
    const asked = await api(page);
    await page.goto(address);
    const opener = page.getByRole("button", { name: "Ask Toto" });
    await expect(opener).toBeInViewport();
    await expectNoHorizontalOverflow(page);
    await opener.click();

    const panel = page.getByRole("dialog", { name: "Ask Toto" });
    await expect(panel).toHaveAttribute("aria-modal", "true");
    await expect(panel.getByText("Toto is an AI guide that answers from otdog's guides.", { exact: false })).toBeVisible();
    await expect(panel.getByRole("textbox", { name: "Your question" })).toBeFocused();
    const box = (await panel.boundingBox())!;
    expect(box.x).toBe(0);
    expect(box.width).toBe(360);
    await expectNoHorizontalOverflow(page);
    await expectNoAxeViolations(page, "#toto-panel");

    await panel.getByRole("button", { name: "What is a doc fee?" }).click();
    const history = panel.getByRole("list", { name: "Your questions and Toto's answers" });
    await expect(history.getByRole("link", { name: "Which dealer fees to ask about" })).toBeVisible();
    await panel.getByRole("textbox", { name: "Your question" }).fill("What is a spot delivery?");
    await page.keyboard.press("Enter");
    await expect(history.getByText(FALLBACK.message)).toBeVisible();
    await panel.getByRole("textbox", { name: "Your question" }).fill("And a trade-in?");
    await page.keyboard.press("Enter");
    await expect(history.getByText("Toto is resting until tomorrow. The guides are still here.")).toBeVisible();
    expect(asked).toHaveLength(3);
    expect((asked[2] as { turns: unknown[] }).turns).toHaveLength(4);
    await expect(panel.getByRole("textbox", { name: "Your question" })).toBeInViewport();
    await expectNoHorizontalOverflow(page);
    await expectNoAxeViolations(page, "#toto-panel");

    await page.keyboard.press("Escape");
    await expect(panel).toBeHidden();
    await expect(opener).toBeFocused();
  });
}

test("following a cited guide opens it behind the chat and keeps the chat", async ({ page }) => {
  await api(page);
  await page.goto("/");
  await page.getByRole("button", { name: "Ask Toto" }).click();
  const panel = page.getByRole("dialog", { name: "Ask Toto" });
  await panel.getByRole("button", { name: "What is a doc fee?" }).click();
  await panel.getByRole("link", { name: "Which dealer fees to ask about" }).click();
  await expect(page).toHaveURL(/\?guide=dealer-fees$/);
  await expect(page.getByRole("heading", { level: 1, name: "Which dealer fees to ask about" })).toBeVisible();
  await expect(panel).toBeHidden();
  await page.getByRole("button", { name: "Ask Toto" }).click();
  await expect(panel.getByText("What is a doc fee?")).toBeVisible();
});

test("on a wide screen Ask Toto is a side panel beside the page", async ({ page }) => {
  await api(page);
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.goto("/?guide=dealer-fees");
  await page.getByRole("button", { name: "Ask Toto" }).click();
  const panel = page.getByRole("dialog", { name: "Ask Toto" });
  await expect(panel).not.toHaveAttribute("aria-modal");
  const box = (await panel.boundingBox())!;
  expect(box.width).toBe(420);
  expect(box.x + box.width).toBe(1280);
  await expect(page.locator(".toto-backdrop")).toHaveCount(0);
  await expectNoHorizontalOverflow(page);
  await expectNoAxeViolations(page, "#toto-panel");
});

test("when Toto is unavailable nothing about the page changes", async ({ page }) => {
  await api(page, false);
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Start your Buying Case" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Ask Toto" })).toHaveCount(0);
  await expect(page.locator("#toto-panel")).toHaveCount(0);
});

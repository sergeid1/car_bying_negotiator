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

const GUIDES = [
  { slug: "out-the-door-quote", title: "How to read an out-the-door quote", channel: "guide-otd-quote" },
  { slug: "dealer-fees", title: "Which dealer fees to ask about", channel: "guide-dealer-fees" },
];

/** The layout gate needs no real service, only the case shape a guide arrival starts. */
async function stubCaseApi(page: Page, created: (body: Record<string, unknown>) => void) {
  await page.route("**/api/v1/**", route => route.fulfill({ status: 204, body: "" }));
  await page.route("**/api/v1/intake-options", route => route.fulfill({ json: OPTIONS }));
  await page.route("**/api/v1/cases", route => {
    created(route.request().postDataJSON());
    return route.fulfill({ status: 201, json: { case: { id: "case-1", referralDisclosure: null }, accessToken: "case-secret" } });
  });
  await page.route("**/api/v1/cases/case-1/vehicle-evidence", route =>
    route.fulfill({ json: { identityConflicts: [], reliableForAnalysis: true } }));
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

test("the start screen links to both guides at 360 px", async ({ page }) => {
  await stubCaseApi(page, () => {});
  await page.goto("/");
  const links = page.getByRole("region", { name: "Guides to reading your quote" });
  await expect(links).toBeVisible();
  await expectNoHorizontalOverflow(page);
  await links.getByRole("link", { name: GUIDES[1].title }).click();
  await expect(page.getByRole("heading", { level: 1, name: GUIDES[1].title })).toBeVisible();
  expect(new URL(page.url()).search).toBe(`?guide=${GUIDES[1].slug}`);
});

for (const guide of GUIDES) {
  test(`the ${guide.slug} guide and its start action fit 360 px without horizontal scrolling`, async ({ page, baseURL }) => {
    let creation: Record<string, unknown> = {};
    const requested: string[] = [];
    page.on("request", request => requested.push(request.url()));
    await stubCaseApi(page, body => { creation = body; });

    await page.goto(`/?guide=${guide.slug}`);
    await expect(page.getByRole("heading", { level: 1, name: guide.title })).toBeVisible();
    await expectNoHorizontalOverflow(page);
    const results = await new AxeBuilder({ page })
      .include("article.guide")
      .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
      .analyze();
    expect(results.violations).toEqual([]);

    await page.getByRole("link", { name: "Start a case with your quote" }).click();
    await expect(page.getByRole("heading", { name: "Start your Buying Case" })).toBeVisible();
    expect(new URL(page.url()).search).toBe("");
    await expectNoHorizontalOverflow(page);

    await page.getByLabel(/your zip code/i).fill("27601");
    await page.getByLabel(/dealer zip code/i).fill("27604");
    await page.getByLabel(/^vin$/i).fill("1HGCM82633A004352");
    await page.getByRole("button", { name: /start my case/i }).click();
    await expect(page.getByRole("navigation", { name: "Case progress" })).toBeVisible();
    expect(creation.channel).toBe(guide.channel);

    // The channel code is the only tracking: no cookies, no storage, nothing from another site.
    expect(await page.context().cookies()).toEqual([]);
    expect(await page.evaluate(() => localStorage.length + sessionStorage.length)).toBe(0);
    const origin = new URL(baseURL!).origin;
    expect(requested.filter(url => !url.startsWith(origin) && !url.startsWith("data:"))).toEqual([]);
  });
}

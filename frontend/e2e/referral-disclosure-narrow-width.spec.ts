import AxeBuilder from "@axe-core/playwright";
import { expect, Page, test } from "@playwright/test";
import { openCaseStep } from "./case-steps";

const OPTIONS = {
  supported: ["US-NC"],
  consentPolicies: {
    serviceProcessing: "service-processing.v1",
    humanReview: "human-review.v1",
    anonymizedResearch: "anonymized-research.v1",
  },
};

const DISCLOSURE =
  "Example Independent Vehicle Inspections, a consumer inspection service, refers Buyers here. It is not paid for referrals and does not pay otdog.";

/** The layout gate needs no real service, only the case shape a referral arrival renders from. */
async function stubCaseApi(page: Page, created: (body: Record<string, unknown>) => void) {
  await page.route("**/api/v1/**", route => route.fulfill({ status: 204, body: "" }));
  await page.route("**/api/v1/intake-options", route => route.fulfill({ json: OPTIONS }));
  await page.route("**/api/v1/cases", route => {
    created(route.request().postDataJSON());
    return route.fulfill({
      status: 201,
      json: { case: { id: "case-1", referralDisclosure: DISCLOSURE }, accessToken: "case-secret" },
    });
  });
  await page.route("**/api/v1/cases/case-1/vehicle-evidence", route =>
    route.fulfill({ json: { identityConflicts: [], reliableForAnalysis: true } }));
}

test("a referral arrival shows its disclosure before upload at 360 px without horizontal scrolling", async ({ page }) => {
  let creation: Record<string, unknown> = {};
  await stubCaseApi(page, body => { creation = body; });
  await page.goto("/?channel=inspector");
  await expect(page.getByRole("heading", { name: "Start your Buying Case" })).toBeVisible();
  expect(new URL(page.url()).search).toBe("");

  await page.getByLabel(/your zip code/i).fill("27601");
  await page.getByLabel(/dealer zip code/i).fill("27604");
  await page.getByLabel(/^vin$/i).fill("1HGCM82633A004352");
  await page.getByRole("button", { name: /start my case/i }).click();

  const disclosure = page.getByRole("region", { name: "How you reached us" });
  await expect(disclosure).toContainText(DISCLOSURE);
  await openCaseStep(page, "At the dealer");
  const upload = page.getByRole("heading", { name: /add the dealer's paperwork/i });
  await expect(upload).toBeVisible();
  expect(await disclosure.evaluate((element, heading) => Boolean(element.compareDocumentPosition(heading!) & Node.DOCUMENT_POSITION_FOLLOWING),
    await upload.elementHandle())).toBe(true);
  expect(creation.channel).toBe("inspector");
  expect(await page.context().cookies()).toEqual([]);
  expect(await page.evaluate(() => localStorage.length + sessionStorage.length)).toBe(0);

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

  const results = await new AxeBuilder({ page })
    .include("[aria-labelledby='referral-heading']")
    .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
    .analyze();
  expect(results.violations).toEqual([]);
});

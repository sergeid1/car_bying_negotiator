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

const LIMITS = {
  acceptedMediaTypes: ["application/pdf", "image/heic", "image/jpeg", "image/png"],
  maxFileBytes: 20971520,
  maxPages: 25,
  maxPixels: 40000000,
  minWidth: 1,
  minHeight: 1,
};

const PAGES = [{
  pageNumber: 1,
  likelyMissing: false,
  likelyDuplicate: false,
  likelyRotated: true,
  likelyCropped: false,
}];

/** A one-pixel PNG is enough: the browser sends bytes and the stubbed service accepts them. */
const PNG = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
  "base64");

/** The layout gate needs no real service, only the shapes the case view renders from. */
async function stubCaseApi(page: Page) {
  await page.route("**/api/v1/**", route => route.fulfill({ status: 204, body: "" }));
  await page.route("**/api/v1/intake-options", route => route.fulfill({ json: OPTIONS }));
  await page.route("**/api/v1/document-limits", route => route.fulfill({ json: LIMITS }));
  await page.route("**/api/v1/cases", route =>
    route.fulfill({ status: 201, json: { case: { id: "case-1" }, accessToken: "case-secret" } }));
  await page.route("**/api/v1/cases/case-1/vehicle-evidence", route =>
    route.fulfill({ json: { identityConflicts: [], reliableForAnalysis: true } }));
  await page.route("**/api/v1/cases/case-1/documents", route =>
    route.fulfill({
      status: 202,
      json: {
        document: { id: "doc-1", originalFilename: "dealer-order.png", pages: PAGES },
        operation: { id: "op-1", documentId: "doc-1", stage: "QUEUED", retryCount: 0 },
      },
    }));
  await page.route("**/api/v1/cases/case-1/documents/doc-1", route =>
    route.fulfill({ json: { id: "doc-1", pageCount: 1, pages: PAGES } }));
  await page.route("**/api/v1/operations/op-1", route =>
    route.fulfill({
      json: {
        id: "op-1", documentId: "doc-1", stage: "EXTRACTION_COMPLETE", retryCount: 0,
        updatedAt: "2026-09-29T12:00:00Z",
      },
    }));
}

/** The Buyer reaches document intake only through a started case, so the gate starts one. */
async function openCase(page: Page) {
  await page.goto("/");
  await page.getByLabel(/your zip code/i).fill("27601");
  await page.getByLabel(/dealer zip code/i).fill("27604");
  await page.getByLabel(/^vin$/i).fill("1HGCM82633A004352");
  await page.getByRole("button", { name: /start my case/i }).click();
    await openCaseStep(page, "At the dealer");
  await expect(page.getByRole("heading", { name: /add the dealer's paperwork/i })).toBeVisible();
}

test.beforeEach(async ({ page }) => {
  await stubCaseApi(page);
  await openCase(page);
});

test("paperwork can be attached and followed at 360 px without horizontal scrolling", async ({ page }) => {
  await page.getByLabel(/dealer paperwork/i).setInputFiles({
    name: "dealer-order.png", mimeType: "image/png", buffer: PNG,
  });

  await expect(page.getByText(/terms read/i)).toBeVisible();
  await expect(page.getByText(/page 1 looks rotated/i)).toBeVisible();
  await expect(page.getByRole("button", { name: /add or replace pages/i })).toBeVisible();

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

test("both file controls are labelled, reachable, and stacked in one column at 360 px", async ({ page }) => {
  const panel = page.getByRole("region", { name: /add the dealer's paperwork/i });
  const controls = panel.locator("input[type=file]");
  await expect(controls).toHaveCount(2);

  const boxes = await controls.evaluateAll(elements =>
    elements.map(element => element.getBoundingClientRect()).map(box => ({
      left: Math.round(box.left), top: Math.round(box.top), bottom: Math.round(box.bottom),
    })));
  // A second column would put two controls at different x positions on the same row.
  expect(boxes[1].top).toBeGreaterThanOrEqual(boxes[0].bottom - 1);
  expect(boxes[0].left).toBe(boxes[1].left);

  for (let index = 0; index < 2; index += 1) {
    const control = controls.nth(index);
    await expect(control).toHaveAccessibleName(/\S/);
    await control.focus();
    const focusRing = await control.evaluate(element => {
      const style = getComputedStyle(element);
      return { width: style.outlineWidth, style: style.outlineStyle, shadow: style.boxShadow };
    });
    const outlined = focusRing.style !== "none" && parseFloat(focusRing.width) > 0;
    expect(outlined || focusRing.shadow !== "none").toBe(true);
  }
});

test("the case view with attached paperwork has no AA violations, including colour contrast", async ({ page }) => {
  await page.getByLabel(/dealer paperwork/i).setInputFiles({
    name: "dealer-order.png", mimeType: "image/png", buffer: PNG,
  });
  await expect(page.getByText(/terms read/i)).toBeVisible();

  const results = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
    .analyze();

  expect(results.violations.map(violation => ({
    id: violation.id,
    nodes: violation.nodes.map(node => node.html),
  }))).toEqual([]);
});

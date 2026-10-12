import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";

const VIN = "1HGCM82633A004352";
function routing(state: string) {
  return { status: state === "TYPED" ? "UNREADABLE" : "READ", documentId: "d1", documentType: "FOUR_SQUARE_WORKSHEET",
    vin: state === "TYPED" ? null : state === "FRESH_CASE" ? "1M8GDM9AXKP042788" : VIN,
    route: state === "FRESH_CASE" ? "FRESH_CASE" : "THIS_CASE", agreedVin: null, caseVin: state === "FRESH_CASE" ? VIN : null,
    freshCase: state === "FRESH_CASE" ? { vin: "1M8GDM9AXKP042788", dealerLocation: "Raleigh, NC", purchaseMethod: "CASH" } : null,
    questions: { trade: { ask: "WHETHER", paperworkShowsTrade: false, recorded: null },
      financing: { ask: true, paperworkShowsLoan: true, ownAprPercent: null, ownTermMonths: null } } };
}
const lead = { code: "ADD_ON", summary: "Vehicle service contract with extended powertrain and wear-item coverage, $1,495, is an add-on.",
  amountMinor: 149500, deskQuestion: "Is the Vehicle service contract with extended powertrain and wear-item coverage optional? Please show me the price without it." };
const result = { route: "THIS_CASE", source: "READ_FROM_PAPERWORK", outTheDoor: { amountMinor: 2829500, agreedMinor: null, differenceMinor: null },
  lead, findings: [lead, { code: "APR_ABOVE_OWN_OFFER", summary: "The dealer's APR of 7.9% is above your own lender's 5.9%, about $2,140 more in payments over the loan.",
    amountMinor: 214000, deskQuestion: "My own lender offered 5.9% APR. Will you match it?" }] };

async function stub(page: Page, state: string) {
  await page.route("**/api/v1/**", route => route.fulfill({ status: 204, body: "" }));
  await page.route("**/api/v1/intake-options", route => route.fulfill({ json: { supported: ["US-NC"], consentPolicies: {
    serviceProcessing: "service-processing.v1", humanReview: "human-review.v1", anonymizedResearch: "anonymized-research.v1" } } }));
  await page.route("**/api/v1/document-limits", route => route.fulfill({ json: { acceptedMediaTypes: ["application/pdf", "image/heic", "image/jpeg", "image/png"],
    maxFileBytes: 20971520, maxPages: 25, maxPixels: 40000000, minWidth: 2, minHeight: 2 } }));
  await page.route("**/api/v1/cases", route => route.fulfill({ status: 201, json: { case: { id: "case-1" }, accessToken: "secret" } }));
  await page.route("**/api/v1/cases/case-1/documents", route => route.fulfill({ status: 202,
    json: { document: { id: "d1", pages: [] }, operation: { id: "op1", stage: "QUEUED" } } }));
  await page.route("**/api/v1/operations/op1", route => route.fulfill({ json: { id: "op1", documentId: "d1",
    stage: state === "TYPED" ? "FAILED" : "EXTRACTION_COMPLETE", errorCode: state === "TYPED" ? "EXTRACTION_FAILED" : null, retryCount: 0, updatedAt: "now" } }));
  await page.route("**/api/v1/cases/case-1/documents/d1", route => route.fulfill({ json: { pages: [] } }));
  await page.route("**/api/v1/cases/case-1/desk-start?**", route => route.fulfill({ json: routing(state) }));
  await page.route("**/api/v1/cases/case-1/desk-start", route => route.fulfill({ json: routing(state) }));
  await page.route("**/api/v1/cases/case-1/desk-start/result", route => route.fulfill({ json: result }));
  await page.route("**/api/v1/cases/case-1/vehicle-evidence", route => route.fulfill({ json: { identityConflicts: [], reliableForAnalysis: true } }));
}

// The narrow and narrow-dark projects run each state in both themes.
for (const state of ["RESULT", "TYPED", "FRESH_CASE"]) {
  test(`I'm at the dealer ${state} works one-handed at 360 px`, async ({ page }) => {
    await stub(page, state);
    await page.goto("/");
    // One tap from the header reaches the camera: the control is the camera input's own label.
    const entry = page.locator(".header-camera");
    await expect(entry).toBeInViewport();
    expect((await entry.boundingBox())!.height).toBeGreaterThanOrEqual(44);
    const camera = page.getByLabel(/I'm at the dealer/);
    await expect(camera).toHaveAttribute("capture", "environment");
    await camera.setInputFiles({ name: "four-square.jpg", mimeType: "image/jpeg", buffer: Buffer.from("not a real photo") });
    const flow = page.locator(".at-the-dealer");
    await expect(flow.getByRole("heading", { name: "Check the paperwork in front of you" })).toBeInViewport();
    await flow.getByLabel("Your ZIP code").fill("27601");
    await flow.getByLabel("Dealer city").fill("27604");
    await flow.getByLabel("Dealer state").selectOption("NC");
    await flow.getByRole("button", { name: "Read my paperwork" }).click();
    if (state === "RESULT") {
      await flow.getByRole("button", { name: "Yes, check this car" }).click();
      await flow.getByRole("group", { name: "Are you trading a car in?" }).getByLabel("No").click();
      await flow.getByRole("group", { name: /own loan offer/ }).getByLabel("Yes").click();
      await flow.getByLabel("Its APR (%)").fill("5.9");
      await flow.getByRole("button", { name: "Show my result" }).click();
      // The out-the-door total, the largest finding and its desk question are on screen without scrolling.
      const first = page.locator(".dealer-result");
      const total = first.getByRole("heading", { name: "Out the door: $28,295" });
      await expect(total).toBeInViewport({ ratio: 1 });
      await page.waitForTimeout(300);
      // Clear of the sticky masthead, not merely inside the viewport behind it.
      const bar = (await page.locator(".masthead").boundingBox())!;
      expect((await total.boundingBox())!.y).toBeGreaterThanOrEqual(bar.y + bar.height);
      await expect(first.locator(".dealer-lead-summary")).toBeInViewport({ ratio: 1 });
      await expect(first.locator(".dealer-question q")).toBeInViewport({ ratio: 1 });
      // The marks a phone's remote inspector reads in the field; here the API is stubbed, so this is the client's share.
      const read = Number(await first.getAttribute("data-read-ms"));
      expect(read).toBeGreaterThan(0);
      console.log(`desk-start photo-to-routing ms (stubbed API, ${test.info().project.name}): ${read}`);
      await first.getByText("1 more to ask about").click();
    }
    if (state === "TYPED") {
      await expect(flow.getByRole("heading", { name: "This photo could not be read. Type the numbers instead" })).toBeVisible();
      await flow.getByLabel("Selling price").fill("about 25k");
      await flow.getByRole("button", { name: "Check these numbers" }).click();
      await expect(flow.getByRole("alert").first()).toContainText("Enter a dollar amount");
    }
    if (state === "FRESH_CASE") {
      await expect(flow.getByRole("alert").first()).toContainText("It shows VIN 1M8GDM9AXKP042788");
      await flow.getByRole("button", { name: "Start a separate case for this car" }).click();
      await expect(flow.getByLabel("Dealer city")).toHaveValue("Raleigh");
      await expect(flow.getByLabel("Dealer state")).toHaveValue("NC");
    }
    // Let the theme cross-fade and entrance animation settle before measuring colours and layout.
    await page.waitForTimeout(600);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
    for (const button of await page.locator(".at-the-dealer .panel button, .dealer-result button").all())
      if (await button.isVisible()) expect((await button.boundingBox())!.height).toBeGreaterThanOrEqual(44);
    const results = await new AxeBuilder({ page }).include(".at-the-dealer").include(".masthead")
      .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"]).analyze();
    expect(results.violations.map(violation => ({ id: violation.id, nodes: violation.nodes.map(node => node.html) }))).toEqual([]);
  });
}

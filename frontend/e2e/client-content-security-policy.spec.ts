import { expect, Page, test } from "@playwright/test";
import headers from "../client-headers.json" with { type: "json" };
import { openCaseStep } from "./case-steps";

// Both Playwright projects run these Buyer journeys in light and dark themes.
const PNG = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=", "base64");
const candidate = { id: "price", field: "SELLING_PRICE", originalWording: "Selling price $25,000",
  normalizedValue: "2500000", normalizedUnit: "USD_MINOR", confidence: 0.98, material: true,
  evidence: { pageNumber: 1, x: 0.1, y: 0.1, width: 0.5, height: 0.1 },
  verificationState: "UNVERIFIED", currentCorrection: null, correctionHistory: [] };
const extraction = { id: "ext", documentId: "doc", version: 1, extractorVersion: "fixture.v1",
  modelVersion: "fixture.v1", candidates: [candidate], lineItems: [] };
const report = { version: 1, asOf: "2026-10-06", status: "COMPLETE", analysis: {
  reviewLabel: "Review required", dealRating: "FAIR", ratingBasis: "Recorded evidence", nextAction: "Review the fees",
  topReasons: ["Review the recorded fees."], marketAvailability: "UNAVAILABLE", cashAvailability: "AVAILABLE",
  materialAmounts: [{ label: "/result/outTheDoorTotal", minorUnits: 2850000, currency: "USD", precision: "ESTIMATED",
    source: "cashEconomics", pointer: "/result/outTheDoorTotal", documentSource: {
      documentId: "doc", extractionId: "ext", offerVersion: 1, wording: "Total", pageNumber: 1, region: candidate.evidence,
    } }], policyVersions: {}, limitations: ["Financial consistency only."],
}, evidence: {}, reproduction: {} };

async function api(page: Page, mode: "upload" | "verify" | "report" | "expired") {
  await page.route("**/api/v1/**", async route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith("/image")) return route.fulfill({ contentType: "image/png", body: PNG });
    if (path === "/api/v1/account") return route.fulfill({ status: 401, json: {} });
    if (path === "/api/v1/cases/current" && mode === "expired") return route.fulfill({ status: 404, json: {} });
    if (path.endsWith("documents") && route.request().method() === "POST") return route.fulfill({ status: 202,
      json: { document: { id: "doc", originalFilename: "order.png", pages: [] },
        operation: { id: "upload", documentId: "doc", stage: "QUEUED", retryCount: 0 } } });
    const body = path.endsWith("intake-options") ? { supported: ["US-NC"], consentPolicies: {
      serviceProcessing: "service-processing.v1", humanReview: "human-review.v1", anonymizedResearch: "anonymized-research.v1" } }
      : path.endsWith("document-limits") ? { acceptedMediaTypes: ["image/png"], maxFileBytes: 20971520, maxPages: 25,
        maxPixels: 40000000, minWidth: 1, minHeight: 1 }
      : path === "/api/v1/cases" ? { case: { id: "current" }, accessToken: "secret" }
      : path === "/api/v1/cases/current" ? { id: "current" }
      : path.endsWith("vehicle-evidence") ? { identityConflicts: [], reliableForAnalysis: true }
      : path.endsWith("documents") ? (mode === "verify" ? [{ id: "doc", originalFilename: "order.png" }] : [])
      : path.endsWith("documents/doc") ? { id: "doc", pageCount: 1, pages: [] }
      : path.endsWith("extractions/latest") ? extraction
      : path.endsWith("operations/upload") ? { id: "upload", documentId: "doc", stage: "EXTRACTION_COMPLETE", retryCount: 0 }
      : path.endsWith("dealer-offers/latest") ? (mode === "report" ? { version: 1, status: "VERIFIED" } : {})
      : path.endsWith("analyses") ? { operation: { id: "analysis", stage: "QUEUED" } }
      : path.endsWith("operations/analysis") ? { stage: "COMPLETE" }
      : path.endsWith("reports/latest") ? report
      : path.endsWith("negotiation") ? { language: { draft: "Please explain the fees.", items: [] } }
      : path.endsWith("feedback") ? { experiment: null } : {};
    await route.fulfill({ json: body });
  });
}

async function start(page: Page) {
  await page.goto("/");
  await page.getByLabel(/your zip code/i).fill("27601");
  await page.getByLabel(/dealer zip code/i).fill("27604");
  await page.getByLabel(/^vin$/i).fill("1HGCM82633A004352");
  await page.getByRole("button", { name: /start my case/i }).click();
}

const violations = new WeakMap<object, string[]>();
test.use({ page: async ({ context }, use) => {
  const page = await context.newPage();
  const recorded: string[] = [];
  violations.set(page, recorded);
  await page.exposeBinding("recordCspViolation", (_source, value: string) => recorded.push(value));
  await page.addInitScript(() => {
    document.addEventListener("securitypolicyviolation", event => {
      // Do not capture the URL fragment or credential-bearing application content.
      void (window as unknown as { recordCspViolation: (value: string) => Promise<void> })
        .recordCspViolation(`${event.effectiveDirective}: ${event.blockedURI.split("#")[0]}`);
    });
  });
  await use(page);
  await page.close();
} });
test.afterEach(async ({ page }) => {
  // Flush browser event delivery before inspecting the Node-side record (survives navigation).
  if (!page.isClosed()) await page.evaluate(() => new Promise(resolve => setTimeout(resolve, 0)));
  expect(violations.get(page), "No CSP violations on Buyer paths").toEqual([]);
});

test("the built client serves the deployment's complete response header set", async ({ request }) => {
  const response = await request.get("/");
  expect(response.ok()).toBe(true);
  for (const [name, value] of Object.entries(headers)) {
    expect(response.headers()[name.toLowerCase()], name).toBe(value);
  }
});

test("intake and document upload work under the policy", async ({ page }) => {
  await api(page, "upload");
  await start(page);
  await openCaseStep(page, "At the dealer");
  await expect(page.getByRole("heading", { name: /add the dealer's paperwork/i })).toBeVisible();
  await page.getByLabel(/dealer paperwork/i).setInputFiles({ name: "order.png", mimeType: "image/png", buffer: PNG });
  await expect(page.getByText(/terms read/i)).toBeVisible();
});

test("verification displays authenticated source pages and CSSOM region outlines", async ({ page }) => {
  await api(page, "verify");
  await start(page);
  await openCaseStep(page, "Compare & negotiate");
  const image = page.getByRole("img", { name: /where selling price was read/i });
  await expect(image).toBeVisible();
  await expect(image).toHaveAttribute("src", /^blob:/);
  await expect.poll(() => image.evaluate(element => (element as HTMLImageElement).naturalWidth)).toBeGreaterThan(0);
  await expect(page.locator(".source-region").first()).toHaveCSS("left", /.+px/);
  await page.getByRole("button", { name: "Confirm Selling price", exact: true }).click();
  await expect(page.getByText("Every term has your decision.")).toBeVisible();
});

test("saved report, evidence, negotiation, downloads and theme reload work under the policy", async ({ page }, testInfo) => {
  const theme = testInfo.project.name === "narrow-dark" ? "dark" : "light";
  await page.addInitScript(value => localStorage.setItem("deal-xray-theme", value), theme);
  await api(page, "report");
  await page.goto("/#case=current&access=secret");
  await expect(page.getByRole("heading", { name: "Deal Analysis Report", exact: true })).toBeVisible();
  await expect(page.locator("html")).toHaveAttribute("data-theme", theme);
  expect(new URL(page.url()).hash).toBe("");
  await page.locator("summary", { hasText: "Evidence and calculations" }).click();
  await page.getByRole("button", { name: "Show page 1", exact: true }).click();
  const image = page.getByRole("img", { name: /where out The Door Total was read/i });
  await expect(image).toBeVisible();
  await expect(image).toHaveAttribute("src", /^blob:/);
  await expect.poll(() => image.evaluate(element => (element as HTMLImageElement).naturalWidth)).toBeGreaterThan(0);
  await expect(page.getByRole("region", { name: "Questions to ask the dealer" })).toBeVisible();
  await page.getByRole("textbox", { name: "Your negotiation draft", exact: true }).fill("Please explain this fee.");
  const downloaded = page.waitForEvent("download");
  await page.getByRole("link", { name: "Download draft", exact: true }).click();
  expect((await downloaded).suggestedFilename()).toMatch(/^negotiation-report-/);
  await page.locator("summary", { hasText: "Save a copy" }).click();
  await expect(page.locator("#core-downloads")).toBeVisible();
  await page.reload();
  await expect(page.locator("html")).toHaveAttribute("data-theme", theme);
});

test("expired saved links expose recovery under the policy", async ({ page }) => {
  await api(page, "expired");
  await page.goto("/#case=current&access=secret");
  await expect(page.getByRole("alert")).toContainText(/may have expired, been revoked, or be unrecognized/);
  await expect(page.getByRole("button", { name: "Email recovery link", exact: true })).toBeVisible();
});

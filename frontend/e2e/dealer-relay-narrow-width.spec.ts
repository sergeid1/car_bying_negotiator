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

const LONG_DEALER = "internet-sales-department.after-hours@a-very-long-dealership-domain-name.example";
const RELAY = {
  address: "cabcdefghijklmnopqrstuvwxyz234567@relay.invalid", forwardingAvailable: false, messages: [
    { id: "in-1", direction: "INBOUND", trust: "UNTRUSTED", dealerAddress: LONG_DEALER, state: "RECEIVED",
      createdAt: "2026-10-03T12:30:00Z", completedAt: "2026-10-03T12:30:00Z", contentAvailable: true,
      attachments: [{ ordinal: 0, documentId: "d1", outcome: "ACCEPTED" }, { ordinal: 1, documentId: null, outcome: "MALWARE_DETECTED" }] },
    { id: "out-1", direction: "OUTBOUND", trust: "PLATFORM", dealerAddress: LONG_DEALER, state: "DELIVERED",
      createdAt: "2026-10-03T12:00:00Z", completedAt: "2026-10-03T12:01:00Z", contentAvailable: false, attachments: [], sendPath: "RELAY" },
    { id: "out-2", direction: "OUTBOUND", trust: "PLATFORM", dealerAddress: LONG_DEALER, state: "DELIVERY_UNKNOWN",
      createdAt: "2026-10-03T11:00:00Z", completedAt: null, contentAvailable: true, attachments: [], sendPath: "OWN_EMAIL_APP" }],
};
const CONTENT = { subject: "Re: Quote", body: "See https://a-very-long-dealership-domain-name.example/quotes/0123456789abcdef0123456789abcdef for the itemized quote." };

/** The layout gate needs no real service, only the shapes the relay panel renders from. */
async function stubCaseApi(page: Page) {
  await page.route("**/api/v1/**", route => route.fulfill({ status: 204, body: "" }));
  await page.route("**/api/v1/intake-options", route => route.fulfill({ json: OPTIONS }));
  await page.route("**/api/v1/cases", route =>
    route.fulfill({ status: 201, json: { case: { id: "case-1" }, accessToken: "case-secret" } }));
  await page.route("**/api/v1/cases/case-1/vehicle-evidence", route =>
    route.fulfill({ json: { identityConflicts: [], reliableForAnalysis: true } }));
  await page.route("**/api/v1/cases/case-1/relay", route => route.fulfill({ json: RELAY }));
  await page.route("**/api/v1/cases/case-1/relay/messages/in-1", route => route.fulfill({ json: CONTENT }));
}

test("the reply-intake address, messages and dealer text fit 360 px", async ({ page }) => {
  await stubCaseApi(page);
  await page.goto("/");
  await page.getByLabel(/your zip code/i).fill("27601");
  await page.getByLabel(/dealer zip code/i).fill("27604");
  await page.getByLabel(/^vin$/i).fill("1HGCM82633A004352");
  await page.getByRole("button", { name: /start my case/i }).click();
  const panel = page.locator(".relay-panel");
  await expect(panel.getByText(RELAY.address)).toBeVisible();
  await expect(panel.getByText("Forward a dealer's email here")).toBeVisible();
  await expect(panel.getByText(/Forwarding is not switched on yet/)).toBeVisible();
  await expect(panel.getByText(/Sent through the former platform relay/)).toBeVisible();
  await panel.getByRole("button", { name: /Show message from/ }).click();
  await expect(panel.getByText(/for the itemized quote/)).toBeVisible();
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
    .include(".relay-panel")
    .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
    .analyze();
  expect(results.violations.map(violation => ({ id: violation.id, nodes: violation.nodes.map(node => node.html) }))).toEqual([]);
});

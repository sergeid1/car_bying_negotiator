import { AxeBuilder, expect, Page, test } from "./fixtures";

const OPTIONS = {
  supported: ["US-NC"],
  consentPolicies: {
    serviceProcessing: "service-processing.v1",
    humanReview: "human-review.v1",
    anonymizedResearch: "anonymized-research.v1",
  },
};
const DEALER = "internet-sales-department.after-hours@a-very-long-dealership-domain-name.example";
const ADDRESS = "a.rather.long.buyer.address.for.narrow.screens@gmail.example";
const TEXT = "Could you provide an itemized out-the-door quote for VIN 1HGCM82633A004352, including the selling price, taxes, all fees and all add-ons?";
const SUBJECT = "Request for an itemized vehicle quote";
const PASS = { pass: true, gateVersion: "outbound-gate.v3", reasons: [] };
const ACCOUNT = { contact: { email: "buyer@example.com", firstName: null, lastName: null, phone: null }, createdAt: "2026-10-01T12:00:00Z",
  sessionExpiresAt: "2026-10-20T12:00:00Z", cases: [] };

/**
 * A stateful stand-in for the account, mailbox and hand-off endpoints that behaves like the fake mailbox adapter:
 * its consent page is the app itself with a code and state, and the first send has an unclear outcome.
 */
async function stubApi(page: Page) {
  const state = { connected: false, quote: "DRAFT", handoff: "HANDED_OFF", sendPath: "OWN_EMAIL_APP", sends: 0, completed: null as unknown };
  await page.route("**/api/v1/**", route => route.fulfill({ status: 204, body: "" }));
  await page.route("**/api/v1/intake-options", route => route.fulfill({ json: OPTIONS }));
  await page.route("**/api/v1/account", route => route.fulfill({ json: ACCOUNT }));
  await page.route("**/api/v1/account/notifications", route => route.fulfill({ json: [] }));
  const mailbox = () => ({ available: true, providers: ["GOOGLE", "MICROSOFT"], connection: state.connected
    ? { provider: "GOOGLE", providerLabel: "Gmail", address: ADDRESS, connectedAt: "2026-10-06T12:00:00Z" } : null });
  await page.route("**/api/v1/account/mailbox", route => route.fulfill({ json: mailbox() }));
  await page.route("**/api/v1/account/mailbox/authorizations", route => {
    expect(route.request().headers()["account-request"]).toBe("1");
    expect(route.request().postDataJSON()).toEqual({ provider: "GOOGLE" });
    return route.fulfill({ json: { authorizationUrl: "/?code=fake.google.challenge&state=state-1" } });
  });
  await page.route("**/api/v1/account/mailbox/connection", route => {
    if (route.request().method() === "DELETE") { state.connected = false; return route.fulfill({ json: mailbox() }); }
    state.completed = route.request().postDataJSON();
    state.connected = true;
    return route.fulfill({ json: mailbox() });
  });
  await page.route("**/api/v1/cases", route => route.fulfill({ status: 201, json: { case: { id: "case-1" }, accessToken: "case-secret" } }));
  await page.route("**/api/v1/cases/case-1/vehicle-evidence", route => route.fulfill({ json: { identityConflicts: [], reliableForAnalysis: true } }));
  await page.route("**/api/v1/cases/case-1/quote-requests", route => route.fulfill({ json: {
    negotiationState: state.quote === "SENT" ? "AWAITING_DEALER" : "MANDATE_ACTIVE",
    dealers: [{ id: "d1", name: "Triangle Motors" }],
    requests: [{ id: "q1", dealerAddress: DEALER, state: state.quote, messageId: state.quote === "DRAFT" ? null : "m1", approvedAt: null,
      gatedDraft: { draft: { text: TEXT }, decision: PASS } }],
    documents: [],
  } }));
  await page.route("**/api/v1/cases/case-1/quote-requests/q1/approval", route => {
    state.quote = "HANDED_OFF";
    return route.fulfill({ json: { id: "q1", state: "HANDED_OFF", messageId: "m1" } });
  });
  const handoff = () => ({ messageId: "m1", sendPath: state.sendPath, state: state.handoff, dealerAddress: DEALER, subject: SUBJECT,
    body: TEXT, handedOffAt: "2026-10-06T12:00:00Z", sentAt: state.handoff === "SENT" ? "2026-10-06T12:05:00Z" : null });
  await page.route("**/api/v1/cases/case-1/handoffs/m1", route => route.fulfill({ json: handoff() }));
  await page.route("**/api/v1/cases/case-1/handoffs/m1/mailbox-send", route => {
    expect(route.request().headers()["account-request"]).toBe("1");
    expect(route.request().headers()["case-access-token"]).toBe("case-secret");
    state.sends += 1; state.sendPath = "CONNECTED_MAILBOX";
    if (state.sends === 1) { state.handoff = "DELIVERY_UNKNOWN"; return route.fulfill({ json: handoff() }); }
    state.handoff = "SENT"; state.quote = "SENT";
    return route.fulfill({ json: handoff() });
  });
  return state;
}

async function expectFits(page: Page) {
  // A side panel slides in from the right; measure once it has arrived.
  await page.evaluate(() => Promise.all(Array.from(document.querySelectorAll(".side-panel, .side-panel-backdrop"))
    .flatMap(element => element.getAnimations()).map(animation => animation.finished)));
  const overflow = await page.evaluate(() => ({
    documentWidth: document.documentElement.scrollWidth,
    viewportWidth: document.documentElement.clientWidth,
    widest: Array.from(document.querySelectorAll<HTMLElement>("body *"))
      .filter(element => element.getBoundingClientRect().right > document.documentElement.clientWidth + 1)
      .map(element => `${element.tagName.toLowerCase()}.${element.className}`).slice(0, 5),
  }));
  expect(overflow.widest).toEqual([]);
  expect(overflow.documentWidth).toBeLessThanOrEqual(overflow.viewportWidth);
}

async function expectAccessible(page: Page, selector: string) {
  const results = await new AxeBuilder({ page }).include(selector)
    .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"]).analyze();
  expect(results.violations.map(violation => ({ id: violation.id, nodes: violation.nodes.map(node => node.html) }))).toEqual([]);
}

test("connect Gmail, send from it with an unclear first outcome, then disconnect, at 360 px", async ({ page }) => {
  const state = await stubApi(page);
  await page.goto("/");
  // The mailbox connection is part of the Buyer Account, opened from the Buyer's email in the header.
  await page.getByRole("banner").getByRole("button", { name: "buyer@example.com" }).click();
  const account = page.locator("section[aria-label='Buyer Account']");
  const mailbox = account.locator(".mailbox-connection");

  // Connecting goes to the provider's consent page and back; the code leaves the address bar at once.
  await expect(mailbox.getByText(/cannot read your email/)).toBeVisible();
  await expectFits(page);
  await expectAccessible(page, "section[aria-label='Buyer Account']");
  await mailbox.getByRole("button", { name: "Connect Gmail" }).click();
  await expect(mailbox.getByText(/Gmail is connected/)).toBeVisible();
  expect(state.completed).toEqual({ state: "state-1", code: "fake.google.challenge" });
  expect(new URL(page.url()).search).toBe("");
  await expect(mailbox.getByText(ADDRESS, { exact: true })).toBeVisible();
  await expectFits(page);
  await expectAccessible(page, "section[aria-label='Buyer Account']");
  await page.getByRole("button", { name: "Close Your account" }).click();

  // Send from my email sits next to the own-email-app path; an unclear outcome waits for the Buyer.
  await page.getByLabel(/your zip code/i).fill("27601");
  await page.getByLabel(/dealer city/i).fill("27604");
  await page.getByLabel("Dealer state").selectOption("NC");
  await page.getByLabel(/^vin$/i).fill("1HGCM82633A004352");
  await page.getByRole("button", { name: /start my case/i }).click();
  const panel = page.locator("[aria-label='Request a dealer quote'] > .panel").first();
  await panel.getByRole("button", { name: "Approve this text" }).click();
  const handoff = panel.getByRole("group", { name: `Send to ${DEALER} from your email` });
  await expect(handoff.getByRole("link", { name: "Open in my email app" })).toBeVisible();
  await expect(handoff.getByText(ADDRESS, { exact: true })).toBeVisible();
  await expectFits(page);
  await expectAccessible(page, "[aria-label='Request a dealer quote']");
  await handoff.getByRole("button", { name: "Send from my email" }).click();
  await expect(handoff.getByText(/did not say whether this message was sent/)).toBeVisible();
  await expect(handoff.getByText(/Check the Sent folder/)).toBeVisible();
  expect(state.sends).toBe(1);
  await expectFits(page);
  await expectAccessible(page, "[aria-label='Request a dealer quote']");
  await handoff.getByRole("button", { name: "Send again" }).click();
  await expect(panel.getByText("Request sent.")).toBeVisible();
  expect(state.sends).toBe(2);
  await expectFits(page);

  // Disconnecting deletes the access and points back to the own-email-app path.
  await page.getByRole("banner").getByRole("button", { name: "buyer@example.com" }).click();
  await mailbox.getByRole("button", { name: "Disconnect Gmail" }).click();
  await expect(mailbox.getByText(/Gmail is disconnected/)).toBeVisible();
  await expect(mailbox.getByRole("button", { name: "Connect Gmail" })).toBeVisible();
  await expectFits(page);
  await expectAccessible(page, "section[aria-label='Buyer Account']");
});

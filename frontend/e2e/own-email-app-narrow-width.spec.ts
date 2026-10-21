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
const TEXT = "Could you provide an itemized out-the-door quote for VIN 1HGCM82633A004352 (listing: https://a-very-long-dealership-domain-name.example/vehicle/0123456789abcdef), including the selling price, taxes, all fees and all add-ons?";
const SUBJECT = "Request for an itemized vehicle quote";
const PASS = { pass: true, gateVersion: "outbound-gate.v2", reasons: [] };

/** A stateful stand-in for the quote request and hand-off endpoints: approve, hand off, mark sent, add a reply. */
async function stubCaseApi(page: Page) {
  const state = { quote: "DRAFT", handoff: "HANDED_OFF", reply: null as string | null };
  await page.addInitScript(() => {
    (window as unknown as { copied: string[] }).copied = [];
    Object.defineProperty(navigator, "clipboard", { configurable: true, value: {
      writeText: async (text: string) => { (window as unknown as { copied: string[] }).copied.push(text); },
    } });
  });
  await page.route("**/api/v1/**", route => route.fulfill({ status: 204, body: "" }));
  await page.route("**/api/v1/intake-options", route => route.fulfill({ json: OPTIONS }));
  await page.route("**/api/v1/cases", route => route.fulfill({ status: 201, json: { case: { id: "case-1" }, accessToken: "case-secret" } }));
  await page.route("**/api/v1/cases/case-1/vehicle-evidence", route => route.fulfill({ json: { identityConflicts: [], reliableForAnalysis: true } }));
  await page.route("**/api/v1/cases/case-1/quote-requests", route => route.fulfill({ json: {
    negotiationState: state.quote === "SENT" ? state.reply ? "AWAITING_BUYER" : "AWAITING_DEALER" : "MANDATE_ACTIVE",
    // The dealer's email-language choice (ticket 149) and its warning sit on the request card.
    dealers: [{ id: "d1", name: "Triangle Motors", emailLanguage: "en" }],
    requests: [{ id: "q1", dealerAddress: DEALER, state: state.quote, messageId: state.quote === "DRAFT" ? null : "m1", approvedAt: null,
      gatedDraft: { draft: { dealerId: "d1", text: TEXT }, decision: PASS, language: "en" } }],
    documents: state.reply ? [{ documentId: "doc1", outcome: "ACCEPTED", offerVersion: null, sourceKind: "MESSAGE_BODY", messageId: "in1" }] : [],
  } }));
  await page.route("**/api/v1/cases/case-1/quote-requests/q1/approval", route => {
    expect(route.request().postDataJSON()).toEqual({ approved: true, text: TEXT });
    state.quote = "HANDED_OFF";
    return route.fulfill({ json: { id: "q1", state: "HANDED_OFF", messageId: "m1" } });
  });
  const handoff = () => ({ messageId: "m1", sendPath: "OWN_EMAIL_APP", state: state.handoff, dealerAddress: DEALER, subject: SUBJECT,
    body: TEXT, handedOffAt: "2026-10-05T12:00:00Z", sentAt: state.handoff === "SENT" ? "2026-10-05T12:05:00Z" : null });
  await page.route("**/api/v1/cases/case-1/handoffs/m1", route => route.fulfill({ json: handoff() }));
  await page.route("**/api/v1/cases/case-1/handoffs/m1/sent", route => {
    if (state.handoff === "SENT") return route.fulfill({ status: 409, json: { detail: "You already marked this message sent." } });
    state.handoff = "SENT"; state.quote = "SENT";
    return route.fulfill({ json: handoff() });
  });
  await page.route("**/api/v1/cases/case-1/quote-requests/q1/replies", route => {
    expect(route.request().headers()["content-type"]).toContain("multipart/form-data");
    state.reply = route.request().postData() ?? "";
    return route.fulfill({ status: 201, json: { outcome: "RECEIVED", messageId: "in1" } });
  });
  return state;
}

async function expectFits(page: Page) {
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

async function expectAccessible(page: Page) {
  const results = await new AxeBuilder({ page }).include("[aria-label='Request a dealer quote']")
    .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"]).analyze();
  expect(results.violations.map(violation => ({ id: violation.id, nodes: violation.nodes.map(node => node.html) }))).toEqual([]);
}

test("approve, open or copy, mark sent and paste the dealer's reply for a quote request at 360 px", async ({ page }) => {
  const state = await stubCaseApi(page);
  await page.goto("/");
  await page.getByLabel(/your zip code/i).fill("27601");
  await page.getByLabel(/dealer city/i).fill("27604");
  await page.getByLabel("Dealer state").selectOption("NC");
  await page.getByLabel(/^vin$/i).fill("1HGCM82633A004352");
  await page.getByRole("button", { name: /start my case/i }).click();
  const panel = page.locator("[aria-label='Request a dealer quote'] > .panel").first();

  // The dealer's email language is chosen on the request, with its warning, and fits the narrow screen.
  await expect(panel.getByRole("combobox", { name: "Language for this dealer's emails" })).toHaveValue("en");
  await expect(panel.getByText(/The dealer may still answer in English/)).toBeVisible();
  await expectFits(page);
  await expectAccessible(page);

  // Approval hands the exact text to the Buyer; nothing is added to it.
  await panel.getByRole("button", { name: "Approve this text" }).click();
  const handoff = panel.getByRole("group", { name: `Send to ${DEALER} from your email` });
  await expect(handoff.getByText("Not sent yet. Send it from your own email, then tell us.")).toBeVisible();
  await expect(handoff.getByText(/on behalf|AI assistance|opt out/i)).toHaveCount(0);
  const open = handoff.getByRole("link", { name: "Open in my email app" });
  const href = await open.getAttribute("href");
  expect(href).toMatch(/^mailto:internet-sales-department\.after-hours@a-very-long-dealership-domain-name\.example\?subject=/);
  const link = new URL(href!);
  expect(decodeURIComponent(link.search.split("&body=")[1])).toBe(TEXT);
  expect(decodeURIComponent(link.search.slice("?subject=".length).split("&body=")[0])).toBe(SUBJECT);
  await expectFits(page);
  await expectAccessible(page);

  await handoff.getByRole("button", { name: "Copy subject" }).click();
  await expect(handoff.getByText("Subject copied.")).toBeVisible();
  await handoff.getByRole("button", { name: "Copy message" }).click();
  await expect(handoff.getByText("Message copied.")).toBeVisible();
  expect(await page.evaluate(() => (window as unknown as { copied: string[] }).copied)).toEqual([SUBJECT, TEXT]);

  // "I sent it" records the send; the conversation then takes the dealer's pasted reply.
  await handoff.getByRole("button", { name: "I sent it" }).click();
  await expect(panel.getByText("Request sent.")).toBeVisible();
  expect(state.quote).toBe("SENT");
  const reply = panel.getByRole("form", { name: `Add the reply from ${DEALER}` });
  await reply.getByLabel("Dealer's reply").fill("Selling price $25,000 and out-the-door $26,800. https://a-very-long-dealership-domain-name.example/quotes/0123456789abcdef0123456789abcdef");
  await expectFits(page);
  await expectAccessible(page);
  await reply.getByRole("button", { name: "Add reply" }).click();
  await expect(reply.getByText(/Reply added/)).toBeVisible();
  expect(state.reply).toContain("Selling price $25,000");
  await expect(panel.getByText(/Dealer message body: added as a document for your review/)).toBeVisible();
  await expectFits(page);
});

import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";

const pass = { pass: true, gateVersion: "outbound-gate.v2", reasons: [] };
const slot = { option: 1, startsAt: "2026-10-10T15:00:00Z", endsAt: "2026-10-10T16:00:00Z",
  label: "Wednesday, September 30, 2026, 10:00 to 11:00 (America/Argentina/ComodRivadavia)" };
function fixture(state: string) {
  const proposal = { id: "p1", kind: "PROPOSAL", appointmentId: null, slots: [slot], release: [], state: "SENT", createdAt: "2026-10-04T12:00:00Z",
    sentAt: "2026-10-04T12:01:00Z", gatedDraft: { draft: { text: "I'd like to arrange a test drive of the vehicle in your written quote at one of these times:\n\nOption 1: "
      + slot.label + "\n\nPlease reply with the option number that suits you, or suggest other times." }, decision: pass } };
  return { agreedPrice: { id: "ap", offerVersion: 3, baselineVersion: 2, outTheDoorMinor: 2680000 },
    dealerAddress: "internet-sales-department.after-hours@a-very-long-dealership-domain-name.example",
    contactFields: ["FIRST_NAME", "LAST_NAME", "EMAIL", "PHONE"],
    messages: state === "PROPOSE" ? [] : [proposal, ...(state === "CONFIRM" ? [{ ...proposal, id: "b1", kind: "BOOKING", appointmentId: "a1", release: ["FIRST_NAME", "EMAIL"],
      state: "DRAFT", sentAt: null, gatedDraft: { draft: { text: "Thank you for confirming. I'll see you for the test drive on " + slot.label
        + ".\n\nMy acceptance of your quote remains subject to this test drive and to the final paperwork matching the written quote." }, decision: pass } }] : [])],
    appointments: state === "BOOKED" ? [{ id: "a1", option: 1, startsAt: slot.startsAt, endsAt: slot.endsAt, label: slot.label, state: "SCHEDULED",
      replacesAppointmentId: null, release: { fields: ["FIRST_NAME", "LAST_NAME", "EMAIL", "PHONE"], state: "RELEASED", decidedAt: "2026-10-04T13:00:00Z", releasedAt: "2026-10-04T13:01:00Z" } }] : [],
    confirmations: state === "CONFIRM" ? [{ proposalId: "p1", messageId: "m9", receivedAt: "2026-10-04T13:00:00Z",
      excerpt: "Option 1 works. https://a-very-long-dealership-domain-name.example/service/scheduling/confirmation?reference=ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789", detectedOption: 1 }] : [] };
}

for (const state of ["PROPOSE", "CONFIRM", "BOOKED"]) {
  test(`test-drive scheduling ${state} fits 360 px`, async ({ page }) => {
    await page.route("**/api/v1/**", route => route.fulfill({ status: 204, body: "" }));
    await page.route("**/api/v1/intake-options", route => route.fulfill({ json: { supported: ["US-NC"], consentPolicies: {
      serviceProcessing: "service-processing.v1", humanReview: "human-review.v1", anonymizedResearch: "anonymized-research.v1",
    } } }));
    await page.route("**/api/v1/cases", route => route.fulfill({ status: 201, json: { case: { id: "case-1" }, accessToken: "secret" } }));
    await page.route("**/api/v1/cases/case-1/vehicle-evidence", route => route.fulfill({ json: { identityConflicts: [], reliableForAnalysis: true } }));
    await page.route("**/api/v1/cases/case-1/quote-requests", route => route.fulfill({ json: {
      negotiationState: "AGREED", dealers: [], requests: [], documents: [] } }));
    await page.route("**/api/v1/cases/case-1/quote-acceptance", route => route.fulfill({ json: { requests: [],
      agreedPrice: { offerVersion: 3, baselineVersion: 2, actor: "BUYER", acceptedAt: "2026-10-04T12:00:00Z", journey: { journeyLinked: false, otherOpenNegotiations: [] } } } }));
    await page.route("**/api/v1/cases/case-1/appointment", route => route.fulfill({ json: fixture(state) }));
    await page.goto("/");
    await page.getByLabel(/your zip code/i).fill("27601");
    await page.getByLabel(/dealer zip code/i).fill("27604");
    await page.getByLabel(/^vin$/i).fill("1HGCM82633A004352");
    await page.getByRole("button", { name: /start my case/i }).click();
    const panel = page.locator(".test-drive-panel");
    await expect(panel.getByRole("heading", { name: "Schedule a test drive" })).toBeVisible();
    if (state === "PROPOSE") {
      await panel.getByRole("button", { name: "Add another window" }).click();
      await expect(panel.getByRole("group", { name: "Window 2" })).toBeVisible();
    } else if (state === "CONFIRM") {
      await expect(panel.getByRole("checkbox", { name: "Email" })).not.toBeChecked();
      await expect(panel.getByRole("button", { name: "Approve this text with the chosen details" })).toBeVisible();
    } else {
      await expect(panel.getByRole("button", { name: "Prepare a cancellation message" })).toBeVisible();
      await panel.getByRole("button", { name: "Move this test drive" }).click();
      await expect(panel.getByRole("button", { name: "Keep the current time" })).toBeVisible();
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
    const results = await new AxeBuilder({ page }).include(".test-drive-panel").withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"]).analyze();
    expect(results.violations.map(violation => ({ id: violation.id, nodes: violation.nodes.map(node => node.html) }))).toEqual([]);
  });
}

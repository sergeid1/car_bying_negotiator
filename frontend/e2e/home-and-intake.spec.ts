import { expect, test } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";

for (const width of [360, 1440]) {
  test(`home action and simplified intake at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 780 });
    let created = false;
    await page.route("**/api/v1/**", route => {
      const url = new URL(route.request().url());
      if (url.pathname.endsWith("/intake-options")) return route.fulfill({ json: {
        supported: ["US-NC"], buyerState: url.searchParams.has("buyerPostalCode") ? "US-NC" : null,
        consentPolicies: { serviceProcessing: "service.v1", humanReview: "human.v1", anonymizedResearch: "research.v1" },
      } });
      if (url.pathname === "/api/v1/cases" && route.request().method() === "POST") {
        const body = route.request().postDataJSON();
        expect(body).toMatchObject({ buyerPostalCode: "27601", dealerCity: "Raleigh", dealerState: "NC", dealerName: "Buyer-entered dealer",
          buyerLimits: { maxOutTheDoorPriceMinor: 3000000 } });
        expect(body).not.toHaveProperty("dealerPostalCode");
        expect(body.consents.humanReview.granted).toBe(false);
        expect(body.consents.anonymizedResearch.granted).toBe(false);
        return route.fulfill({ json: { case: { id: "case-home" }, accessToken: "test-token" } });
      }
      if (url.pathname.endsWith("/vehicle-evidence") && route.request().method() === "POST") {
        expect(route.request().postDataJSON()).toMatchObject({ manualFacts: { year: 2026, make: "Honda", model: "Accord", trim: "", mileage: 0 },
          listingUrl: "https://example.com/car" });
        created = true;
        return route.fulfill({ json: { identityConflicts: [], reliableForAnalysis: true } });
      }
      return route.fulfill({ status: 204, body: "" });
    });
    await page.goto("/");
    await expect(page.getByRole("button", { name: "Check my deal" })).toBeInViewport({ ratio: 1 });
    await page.screenshot({ path: `test-results/home-${width}-${test.info().project.name}.png` });
    await page.getByRole("button", { name: "Check my deal" }).click();
    await expect(page.getByLabel("Your ZIP code")).toBeFocused();
    await page.getByLabel("Your ZIP code").fill("27601");
    await expect(page.getByLabel("Dealer state")).toHaveValue("NC");
    await page.getByLabel("Dealer name (optional)").fill("Buyer-entered dealer");
    await page.getByLabel("Dealer city").fill("Raleigh");
    await page.getByLabel("Enter the vehicle details").check();
    await page.getByLabel("Year", { exact: true }).fill("2026");
    await page.getByLabel("Make", { exact: true }).fill("Honda");
    await page.getByLabel("Model", { exact: true }).fill("Accord");
    await page.getByLabel("Mileage", { exact: true }).fill("0");
    await expect(page.getByLabel("Listing URL (optional)")).toBeHidden();
    await page.getByText("Add more (optional)", { exact: true }).click();
    await page.getByLabel("Listing URL (optional)").fill("https://example.com/car");
    await page.getByLabel("Most you will pay out the door (optional)").fill("30000");
    await page.getByText("Add more (optional)", { exact: true }).click();
    await page.getByText("Add more (optional)", { exact: true }).click();
    await expect(page.getByLabel("Listing URL (optional)")).toHaveValue("https://example.com/car");
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    expect((await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"]).analyze()).violations).toEqual([]);
    await page.getByRole("button", { name: "Start my case" }).click();
    await expect.poll(() => created).toBe(true);
  });
}

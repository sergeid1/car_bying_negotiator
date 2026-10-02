import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import App from "./App";

/** The shape `GET /api/v1/sample-report` returns: a real report with its identifying fields set to null. */
const SAMPLE = {
  label: "EXAMPLE", source: "SYNTHETIC_CASE",
  redaction: { method: "FIELD_CATEGORIES", version: "sample-redaction.v2", removed: ["VIN", "DEALER_IDENTITY", "LISTING_URL", "DOCUMENT_REFERENCES", "FREE_TEXT"] },
  report: { version: 1, asOf: "2026-09-30", status: "COMPLETE", analysis: {
    reviewLabel: "Review required", dealRating: "FAIR", ratingBasis: "Selling price comparison", nextAction: "Ask the dealer to itemize the fees.",
    topReasons: ["The documentation fee is above the recorded range.", null], marketAvailability: "AVAILABLE", cashAvailability: "AVAILABLE",
    materialAmounts: [
      { label: "Out-the-door total", minorUnits: 2847500, currency: "USD", precision: "EXACT", source: "cashEconomics", pointer: "/result/outTheDoorTotal",
        documentSource: null, sourceNote: null },
      { label: "Doc fee", minorUnits: 89900, currency: "USD", precision: "EXACT", source: "cashEconomics", pointer: "/result/lineItems/0/amount",
        documentSource: { documentId: null, extractionId: null, offerVersion: 1, wording: null, pageNumber: null, region: null }, sourceNote: null },
    ],
    policyVersions: { cash: "cash.v1" }, limitations: ["Financial consistency only."],
  }, evidence: { vehicle: { vin: null } }, reproduction: {} },
};

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

function stub(sample: () => Response) {
  const fetcher = vi.fn(async (path: string) => path === "/api/v1/sample-report" ? sample()
    : path.endsWith("/intake-options") ? json({ supported: ["US-NC"], consentPolicies: { serviceProcessing: "service-processing.v1" } })
    : json({ status: "UNAVAILABLE" }));
  vi.stubGlobal("fetch", fetcher);
  return fetcher;
}

test("the example report opens from the start of the flow without a case, labelled and with removals shown as removed", async () => {
  const fetcher = stub(() => json(SAMPLE));
  render(<App />);
  fireEvent.click(await screen.findByRole("button", { name: "Open the example report" }));
  const panel = screen.getByRole("region", { name: "See an example report" });
  expect(await within(panel).findByRole("heading", { name: "Deal Analysis Report" })).toBeVisible();

  const about = within(panel).getByRole("note", { name: "About this example" });
  expect(about).toHaveTextContent("Example report.");
  expect(about).toHaveTextContent("Built from a synthetic case");
  expect(about).toHaveTextContent("Redaction method: named categories of fields are removed; generated wording and amounts are kept (version sample-redaction.v2).");
  expect(within(about).getByText("Vehicle identification number (VIN)")).toBeVisible();
  expect(within(about).getByText("Dealer name and identity")).toBeVisible();

  expect(within(panel).getAllByText(/Document source: removed from this example/)).toHaveLength(2);
  expect(within(panel).getAllByText("Removed from this example").length).toBeGreaterThan(0);
  expect(within(panel).getByText("Diagnostic data and frozen inputs: removed from this example.")).toBeVisible();
  expect(panel).not.toHaveTextContent(/null|undefined|Unknown/);
  expect(screen.queryByRole("alert")).toBeNull();
  // The example never touches a case: no case token and no case endpoint.
  expect(fetcher.mock.calls.some(([path]) => path.startsWith("/api/v1/cases"))).toBe(false);
});

test("an unavailable example report says so without blocking the case start", async () => {
  stub(() => json({}, 503));
  render(<App />);
  fireEvent.click(await screen.findByRole("button", { name: "Open the example report" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("The example report is unavailable right now.");
  expect(screen.getByRole("heading", { name: "Start your Buying Case" })).toBeVisible();
});

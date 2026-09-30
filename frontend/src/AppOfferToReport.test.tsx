import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import App from "./App";

// Stands in for the verification panel so this test covers only App's hand-off from freeze to analysis.
vi.mock("./OfferVerification", () => ({
  OfferVerification: ({ onOfferFrozen }: { onOfferFrozen?: (offer: { version: number }) => void }) =>
    <button onClick={() => onOfferFrozen?.({ version: 2 })}>Freeze Dealer Offer</button>,
}));

const report = { version: 1, asOf: "2026-09-30", status: "COMPLETE", analysis: {
  reviewLabel: "Review required", dealRating: "FAIR", ratingBasis: "Recorded evidence", nextAction: "Review the fees",
  topReasons: [], marketAvailability: "AVAILABLE", cashAvailability: "AVAILABLE", materialAmounts: [], policyVersions: {}, limitations: [],
}, evidence: {}, reproduction: {} };

afterEach(() => { cleanup(); vi.unstubAllGlobals(); window.history.replaceState(null, "", "/"); });

test("freezing the Dealer Offer runs analysis on that version and opens the report", async () => {
  let frozen = false;
  const fetcher = vi.fn(async (path: string) => {
    if (path.endsWith("/dealer-offers/latest")) {
      return frozen
        ? new Response(JSON.stringify({ version: 2, status: "VERIFIED" }))
        : new Response("{}", { status: 404 });
    }
    const body = path.endsWith("/analyses") ? { operation: { id: "op", stage: "QUEUED" } }
      : path.includes("/operations/") ? { stage: "COMPLETE" }
      : path.endsWith("/reports/latest") ? report
      : path.endsWith("/negotiation") ? { language: { draft: "Review the fees", items: [] } }
      : path === "/api/v1/cases/case-1" || path.endsWith("/market-assessments") || path.endsWith("/cash-economics") ? {}
      : undefined;
    return body ? new Response(JSON.stringify(body)) : new Response("{}", { status: 404 });
  });
  vi.stubGlobal("fetch", fetcher);
  window.history.replaceState(null, "", "/#case=case-1&access=secret");

  render(<App pollDelayMs={0} />);
  expect(await screen.findByText(/verify and freeze your dealer offer to continue/i)).toBeVisible();
  expect(fetcher.mock.calls.some(([path]) => path.endsWith("/analyses"))).toBe(false);

  frozen = true;
  fireEvent.click(screen.getByRole("button", { name: "Freeze Dealer Offer" }));

  expect(await screen.findByRole("heading", { name: "Deal Analysis Report" })).toBeVisible();
  expect(fetcher.mock.calls.filter(([path]) => path === "/api/v1/cases/case-1/analyses")).toHaveLength(1);
});

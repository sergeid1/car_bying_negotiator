import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import App from "./App";

afterEach(() => vi.restoreAllMocks());

test("buyer can start a synthetic cash analysis and reach the rendered report", async () => {
  const responses = [
    {
      case: { id: "case-1", status: "ANALYZING" },
      operation: { id: "operation-1", stage: "QUEUED" },
      accessToken: "case-secret",
    },
    { id: "operation-1", caseId: "case-1", stage: "COMPLETE" },
    {
      schemaVersion: "deal-analysis-report.v1",
      version: 1,
      analysis: {
        reviewStatus: "REVIEW_REQUIRED",
        dealRating: "FAIR_NEGOTIATE",
        topReasons: ["The selling price is within the synthetic market range."],
        nextAction: "Ask the dealer to explain each fee and add-on before deciding.",
        economics: {
          sellingPrice: { minorUnits: 2500000, currency: "USD", precision: "EXACT" },
          dealerFees: { minorUnits: 79900, currency: "USD", precision: "EXACT" },
          addOns: { minorUnits: 50000, currency: "USD", precision: "EXACT" },
          taxes: { minorUnits: 194600, currency: "USD", precision: "EXACT" },
          governmentCharges: { minorUnits: 23000, currency: "USD", precision: "EXACT" },
          outTheDoorTotal: { minorUnits: 2847500, currency: "USD", precision: "EXACT" },
        },
        marketAssessment: {
          low: { minorUnits: 2450000, currency: "USD", precision: "ESTIMATED" },
          high: { minorUnits: 2700000, currency: "USD", precision: "ESTIMATED" },
          confidence: "HIGH",
        },
        limitations: ["Synthetic inputs are for product-path verification only."],
      },
    },
  ];
  vi.spyOn(globalThis, "fetch").mockImplementation(async () =>
    new Response(JSON.stringify(responses.shift()), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    }),
  );

  render(<App pollDelayMs={0} />);
  fireEvent.click(screen.getByRole("button", { name: /analyze synthetic deal/i }));

  await waitFor(() =>
    expect(screen.getByRole("heading", { name: /deal analysis report/i })).toBeVisible(),
  );
  expect(screen.getByText("$28,475.00")).toBeVisible();
  expect(screen.getByText("Review required")).toBeVisible();
  expect(screen.getByText(/ask the dealer to explain/i)).toBeVisible();
});

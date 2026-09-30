import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { CoreDealReport, CoreReport } from "./CoreDealReport";

afterEach(() => cleanup());

it("focuses the outcome and exposes partial status, unavailable money and evidence navigation", () => {
  const report: CoreReport = {
    version: 1, asOf: "2026-09-05T12:00:00Z", status: "PARTIAL",
    analysis: {
      reviewLabel: "Unable to verify", dealRating: "INSUFFICIENT_DATA", ratingBasis: "Selling price only",
      nextAction: "Resolve evidence", topReasons: ["Unknown charge", "Missing market", "Check quote"],
      marketAvailability: "UNAVAILABLE", cashAvailability: "AVAILABLE",
      materialAmounts: [{ label: "Market range", minorUnits: null, currency: "USD", precision: "UNAVAILABLE", source: "marketAssessment", pointer: "" }],
      policyVersions: { review: "core-review.v1" }, limitations: ["Cash purchase only"],
    }, evidence: { marketAssessment: null }, reproduction: {},
  };
  render(<CoreDealReport report={report} />);
  expect(screen.getByRole("heading", { name: "Deal Analysis Report" })).toHaveFocus();
  expect(screen.getByText(/Warning: this report is partial/)).toBeInTheDocument();
  expect(screen.getByRole("navigation", { name: "Report sections" })).toBeInTheDocument();
  expect(screen.getByRole("link", { name: "Source and calculation 1" })).toHaveAttribute("href", "#amount-evidence-0");
  expect(screen.queryByText("$0.00")).not.toBeInTheDocument();
});


it("leads with recorded totals and allows questions to be edited and copied without mutating the payload", async () => {
  const report: CoreReport = { version: 2, status: "COMPLETE", asOf: "2026-09-30", analysis: {
    reviewLabel: "Review required", dealRating: "FAIR", ratingBasis: "Selling price", nextAction: "Request a quote",
    topReasons: ["Check fees", "Check total", "Check market", "Supporting detail"], marketAvailability: "AVAILABLE", cashAvailability: "AVAILABLE",
    materialAmounts: [{ label: "/result/outTheDoorTotal", minorUnits: 2850000, currency: "USD", precision: "ESTIMATED", source: "cashEconomics", pointer: "/result/outTheDoorTotal" }], policyVersions: {}, limitations: [],
  }, evidence: { cashEconomics: { result: { outTheDoorTotal: { minorUnits: 2850000 } } } }, reproduction: {} };
  const before = JSON.stringify(report);
  const copy = vi.fn().mockResolvedValue(undefined);
  Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText: copy } });
  render(<CoreDealReport report={report} />);
  expect(screen.getByRole("link", { name: "$28,500.00" })).toHaveAttribute("href", "#amount-evidence-0");
  fireEvent.change(screen.getByLabelText("Your questions"), { target: { value: "Please itemize the fees." } });
  fireEvent.click(screen.getByRole("button", { name: "Copy questions" }));
  await screen.findByText("Questions copied.");
  expect(copy).toHaveBeenCalledWith("Please itemize the fees.");
  expect(JSON.stringify(report)).toBe(before);
});

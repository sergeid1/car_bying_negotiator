import { render, screen } from "@testing-library/react";
import { expect, it } from "vitest";
import { CoreDealReport, CoreReport } from "./CoreDealReport";

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
  expect(screen.getByRole("link", { name: "Source and calculation 1" })).toHaveAttribute("href", "#evidence-marketAssessment");
  expect(screen.queryByText("$0.00")).not.toBeInTheDocument();
});

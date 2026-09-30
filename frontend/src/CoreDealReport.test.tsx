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

it("resolves amounts to a document page and region or states no source, and writes calculations out as arithmetic", async () => {
  const report: CoreReport = { version: 3, status: "COMPLETE", asOf: "2026-09-30", analysis: {
    reviewLabel: "Review required", dealRating: "FAIR_NEGOTIATE", ratingBasis: "Selling price", nextAction: "Ask about the fee",
    topReasons: ["Check fees"], marketAvailability: "AVAILABLE", cashAvailability: "AVAILABLE",
    dealReview: { version: 1, baselineVersion: 1, previousOfferVersion: 1, currentOfferVersion: 2, status: "REVIEW_REQUIRED",
      checkedAt: "2026-09-30", tolerancePolicy: "USD 1.00", scope: "Verified document terms", discrepancies: [{
        comparison: "PREVIOUS", term: "line:doc fee#1", kind: "CHANGED", expected: { amountMinor: 49900, currency: "USD" },
        observed: { amountMinor: 89900, currency: "USD" }, delta: 40000, unit: "USD_MINOR", severity: "MATERIAL", reason: "Changed",
        expectedEvidence: { originalLabel: "Doc fee", evidence: { pageNumber: 1, x: 0.1, y: 0.6, width: 0.5, height: 0.04 } },
        observedEvidence: { originalLabel: "Doc fee", evidence: { pageNumber: 1, x: 0.1, y: 0.6, width: 0.5, height: 0.04 } },
        confidence: 0.9, uncertain: false, buyerAction: "Ask" }],
      cashDeltas: { previous: { availability: "ESTIMATED", currency: "USD", preTaxDeltaMinor: 40000, taxEffectMinor: 2500, outTheDoorDeltaMinor: 42500,
        expectedOutTheDoorMinor: 2600000, observedOutTheDoorMinor: 2642500, expectedTaxesMinor: 150000, observedTaxesMinor: 152500 } } },
    materialAmounts: [
      { label: "Vehicle price", minorUnits: 2500000, currency: "USD", precision: "EXACT", source: "cashEconomics", pointer: "/result/lineItems/0/amount",
        documentSource: { documentId: "document-1", extractionId: "extraction-1", offerVersion: 2, wording: "Vehicle price", pageNumber: 2,
          region: { x: 0.1, y: 0.2, width: 0.4, height: 0.05 } }, sourceNote: null, calculation: null },
      { label: "/result/vehicleSubtotal", minorUnits: 2450000, currency: "USD", precision: "ESTIMATED", source: "cashEconomics", pointer: "/result/vehicleSubtotal",
        documentSource: null, sourceNote: "Calculated from the inputs below; no single document line holds this amount.",
        calculation: { operands: [{ operator: null, label: "Vehicle price", minorUnits: 2500000, value: null },
          { operator: "−", label: "Dealer discount", minorUnits: 50000, value: null }], notes: [] } },
      { label: "Market selling-price low", minorUnits: 2300000, currency: "USD", precision: "ESTIMATED", source: "marketAssessment", pointer: "/range/lowMinor",
        documentSource: null, sourceNote: "Market estimate from Fixture; not read from a dealer document.", calculation: null },
      { label: "Legacy amount", minorUnits: 100, currency: "USD", precision: "EXACT", source: "cashEconomics", pointer: "/result/legacy" },
    ], policyVersions: {}, limitations: [],
  }, evidence: { cashEconomics: { result: { outTheDoorTotal: { minorUnits: 2642500 } } } }, reproduction: { inputFingerprint: "abc123" } };
  const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(async () => new Response("png", { headers: { "Content-Type": "image/png" } }));
  Object.defineProperty(URL, "createObjectURL", { configurable: true, value: () => "blob:page" });
  Object.defineProperty(URL, "revokeObjectURL", { configurable: true, value: () => undefined });
  const { container } = render(<CoreDealReport report={report} caseId="case-1" token="token-1" />);

  const price = container.querySelector("#amount-evidence-0") as HTMLElement;
  expect(price).toHaveTextContent("Read from page 2 of the dealer document (Dealer Offer version 2), where it says “Vehicle price”.");
  expect(price).toHaveTextContent("Region: 10% from the left and 20% from the top, 40% wide by 5% tall.");
  expect(price.querySelector('[role="img"]')).toHaveAccessibleName("Outline of page 2 with the region where Vehicle price was read");

  const subtotal = screen.getByRole("list", { name: "Calculation of vehicle Subtotal" });
  expect(subtotal).toHaveTextContent("$25,000.00Vehicle price−$500.00Dealer discount=$24,500.00vehicle Subtotal");
  expect(container.querySelector("#amount-evidence-2")).toHaveTextContent("No document source: Market estimate from Fixture; not read from a dealer document.");
  expect(container.querySelector("#amount-evidence-3")).toHaveTextContent("No document source: this saved report does not record where this amount came from.");

  expect(screen.getByText("Current offer taxes $1,525.00 − previous offer taxes $1,500.00 = $25.00")).toBeInTheDocument();
  expect(container.querySelector("pre")).toBeNull();
  expect(container.textContent).not.toMatch(/\{|"\w+"\s*:/);
  expect(container.textContent).not.toContain("abc123");

  const diagnostics = screen.getByText("Diagnostic data for reproducing this report").closest("details")!;
  expect(diagnostics).toHaveTextContent("It is not evidence to read as a Buyer");
  diagnostics.open = true;
  fireEvent(diagnostics, new Event("toggle"));
  expect(await screen.findByText(/"inputFingerprint": "abc123"/)).toBeInTheDocument();

  fireEvent.click(screen.getByRole("button", { name: "Show page 2" }));
  expect(await screen.findByAltText("Page 2 of the dealer document, where Vehicle price was read")).toHaveAttribute("src", "blob:page");
  expect(fetchMock).toHaveBeenCalledWith("/api/v1/cases/case-1/documents/document-1/pages/2/image", { headers: { "Case-Access-Token": "token-1" } });
  fetchMock.mockRestore();
});

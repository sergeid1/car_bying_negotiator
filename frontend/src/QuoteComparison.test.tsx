import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import { ComparisonCar, QuoteComparison, QuoteComparisonView } from "./QuoteComparison";

afterEach(() => { cleanup(); vi.restoreAllMocks(); });
export const comparisonCar = (caseId: string, price: number): ComparisonCar => ({
  caseId, candidateId: caseId, title: `2024 Honda Civic ${caseId}`, state: "Verified quote", offerVersion: 2,
  missing: [], marketPosition: "Selling price within the stored market range.", reportVersion: 3,
  amounts: [{ label: "Selling price", minorUnits: price, currency: "USD", evidence: { sourceNote: null, calculation: null,
    documentSource: { documentId: "doc", extractionId: "ex", offerVersion: 2, wording: "Selling price", pageNumber: 2,
      region: { x: .1, y: .2, width: .3, height: .1 } } } },
    { label: "Out-the-door total", minorUnits: price + 10000, currency: "USD",
      evidence: { documentSource: null, sourceNote: "Quoted on the verified offer; region unavailable.", calculation: null } }],
});
function mock(view: QuoteComparisonView) {
  return vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify(view)));
}
test("starts in shortlist order and changes only by the Buyer's chosen column", async () => {
  const fetcher = mock({ cars: [comparisonCar("first", 2000000), comparisonCar("second", 1900000)], otdComparable: true });
  render(<QuoteComparison journeyId="journey" session="session" onSessionEnded={() => {}} />);
  await screen.findByRole("article", { name: /first/ });
  expect(fetcher).toHaveBeenCalledWith("/api/v1/journeys/journey/quote-comparison", { headers: { "Account-Session-Token": "session" } });
  expect(screen.getAllByRole("article")[0]).toHaveAccessibleName(/first/);
  fireEvent.change(screen.getByLabelText("Order quotes by"), { target: { value: "selling" } });
  expect(screen.getAllByRole("article")[0]).toHaveAccessibleName(/second/);
  fireEvent.change(screen.getByLabelText("Order quotes by"), { target: { value: "shortlist" } });
  const first = screen.getAllByRole("article")[0];
  expect(first).toHaveAccessibleName(/first/);
  const link = within(first).getByRole("link", { name: "Source for Selling price" });
  const evidence = document.getElementById(link.getAttribute("href")!.slice(1))!;
  fireEvent.click(link);
  expect(evidence).toHaveAttribute("open");
  expect(evidence).toHaveTextContent("Read from page 2");
  expect(evidence).toHaveTextContent("Dealer Offer version 2");
});
test("partial quotes disable OTD ordering and missing cars display state without amounts", async () => {
  mock({ cars: [comparisonCar("first", 2000000), { ...comparisonCar("awaiting", 0), state: "Awaiting quote",
    offerVersion: null, amounts: [], missing: ["Taxes", "Out-the-door total"] }], otdComparable: false });
  render(<QuoteComparison journeyId="journey" session="session" onSessionEnded={() => {}} />);
  await screen.findByText(/OTD totals cannot be compared/);
  expect(screen.getByRole("option", { name: "Out-the-door total, lowest first" })).toBeDisabled();
  const car = screen.getByRole("article", { name: /awaiting/ });
  expect(car).toHaveTextContent("Awaiting quote");
  expect(car).toHaveTextContent("Missing: Taxes, Out-the-door total");
  expect(car).not.toHaveTextContent("$0.00");
});
test("refreshes, handles expired sessions and explains load failure", async () => {
  const fetcher = mock({ cars: [], otdComparable: false }); const ended = vi.fn();
  render(<QuoteComparison journeyId="journey" session="session" onSessionEnded={ended} />);
  await screen.findByText(/Open Buying Cases from your shortlist/);
  fetcher.mockResolvedValueOnce(new Response(null, { status: 401 }));
  fireEvent.click(screen.getByRole("button", { name: "Refresh quote comparison" }));
  await vi.waitFor(() => expect(ended).toHaveBeenCalledOnce());
  fetcher.mockResolvedValueOnce(new Response(null, { status: 503 }));
  fireEvent.click(screen.getByRole("button", { name: "Refresh quote comparison" }));
  await screen.findByText(/Quote comparison is unavailable/);
});

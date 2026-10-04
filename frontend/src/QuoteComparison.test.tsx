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
  expect(car).toHaveTextContent("OTD comparison unavailable: Taxes; Out-the-door total");
  expect(car).not.toHaveTextContent("$0.00");
});
test("reconciled omitted categories show None listed without an amount or source and sort as zero", async () => {
  const first = { ...comparisonCar("first", 2000000), noneListed: ["Dealer fees", "Add-ons"] };
  const second = comparisonCar("second", 1900000);
  second.amounts.push({ label: "Dealer fees: Document fee", minorUnits: 50000, currency: "USD",
    evidence: { documentSource: null, sourceNote: "Verified fee.", calculation: null } });
  mock({ cars: [first, second], otdComparable: true });
  render(<QuoteComparison journeyId="journey" session="session" onSessionEnded={() => {}} />);
  await screen.findByText(/Every verified quoted OTD reconciles/);
  const card = screen.getByRole("article", { name: /first/ });
  expect(within(card).getAllByText("None listed")).toHaveLength(2);
  expect(within(card).queryByRole("link", { name: "Source for Add-ons" })).not.toBeInTheDocument();
  expect(card).not.toHaveTextContent("$0.00");
  expect(screen.getByRole("option", { name: "Out-the-door total, lowest first" })).not.toBeDisabled();
  fireEvent.change(screen.getByLabelText("Order quotes by"), { target: { value: "fees" } });
  expect(screen.getAllByRole("article")[0]).toHaveAccessibleName(/first/);
  fireEvent.change(screen.getByLabelText("Order quotes by"), { target: { value: "otd" } });
  expect(screen.getAllByRole("article")[0]).toHaveAccessibleName(/second/);
});
test.each(["fees", "addons"])("%s ordering puts None listed first and missing figures last", async order => {
  const listed = comparisonCar("listed", 1900000);
  listed.amounts.push(...["Dealer fees", "Add-ons"].map(label => ({ label, minorUnits: 50000, currency: "USD",
    evidence: { documentSource: null, sourceNote: "Verified charge.", calculation: null } })));
  const absent = { ...comparisonCar("absent", 2000000), noneListed: ["Dealer fees", "Add-ons"] };
  const missing = { ...comparisonCar("missing", 2000000), missing: ["Dealer fees", "Add-ons"] };
  mock({ cars: [missing, listed, absent], otdComparable: false });
  render(<QuoteComparison journeyId="journey" session="session" onSessionEnded={() => {}} />);
  await screen.findByRole("article", { name: /absent/ });
  fireEvent.change(screen.getByLabelText("Order quotes by"), { target: { value: order } });
  expect(screen.getAllByRole("article").map(card => card.getAttribute("aria-label"))).toEqual([
    absent.title, listed.title, missing.title,
  ]);
});
test("unreconciled quotes show the per-car reason", async () => {
  mock({ cars: [{ ...comparisonCar("one", 2000000), missing: ["Figures do not add up to the quoted OTD by 125.00 USD"] }], otdComparable: false });
  render(<QuoteComparison journeyId="journey" session="session" onSessionEnded={() => {}} />);
  await screen.findByText(/Figures do not add up to the quoted OTD by 125.00 USD/);
  expect(screen.getByRole("option", { name: "Out-the-door total, lowest first" })).toBeDisabled();
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

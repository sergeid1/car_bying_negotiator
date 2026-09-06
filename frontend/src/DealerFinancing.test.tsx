import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { DealerFinancingForm, DealerFinancingResult, type FinancingResult } from "./DealerFinancing";

const result: FinancingResult = { status: "AVAILABLE", timing: "monthly", rounding: "HALF_UP cents",
  issues: [], quotedPaymentMinor: 60000, paymentMinor: 57998, quotedMinusCalculatedMinor: 2002,
  toleranceMinor: 100, withinTolerance: false, possibleCauses: ["Possible causes include rounding, timing and fees."],
  financedAddOns: [{ label: "Protection", cashPriceMinor: 200000, incrementalPaymentMinor: 3866,
    incrementalScheduledPaymentsMinor: 231999, incrementalInterestMinor: 31999 }] };
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
it("shows the payment difference, tolerance and each full-term add-on effect", () => {
  render(<DealerFinancingResult result={result} availability="AVAILABLE" />);
  expect(screen.getByText("$20.02")).toBeInTheDocument();
  expect(screen.getByText("$1.00")).toBeInTheDocument();
  expect(screen.getByText("$2,319.99")).toBeInTheDocument();
  expect(screen.getByText("$319.99")).toBeInTheDocument();
  expect(screen.getByText(/Possible causes/)).toBeInTheDocument();
  expect(screen.getByRole("link", { name: "Verified inputs and calculation" })).toHaveAttribute("href", "#evidence-financing");
});
it("suppresses stale loan amounts and exposes unsupported reasons", () => {
  render(<DealerFinancingResult result={{ ...result, issues: ["Balloon schedules are unsupported."] }} availability="UNSUPPORTED" />);
  expect(screen.getByText("Balloon schedules are unsupported.")).toBeInTheDocument();
  expect(screen.queryByText("$579.98")).not.toBeInTheDocument();
});
it("requires confirmation and preserves unknown fees as null", async () => {
  const fetch = vi.fn().mockResolvedValueOnce({ ok: true, json: async () => ({ id: "dealer-one" }) })
    .mockResolvedValueOnce({ ok: true, json: async () => ({ version: 1, result: { status: "UNAVAILABLE" } }) });
  vi.stubGlobal("fetch", fetch);
  render(<DealerFinancingForm caseId="case-one" token="secret" />);
  const save = screen.getByRole("button", { name: "Save verified Finance Offer", hidden: true });
  expect(save).toBeDisabled();
  fireEvent.change(screen.getByLabelText("APR (%)"), { target: { value: "0" } });
  fireEvent.click(screen.getByRole("checkbox", { hidden: true }));
  fireEvent.click(save);
  await waitFor(() => expect(fetch).toHaveBeenCalledTimes(2));
  const request = JSON.parse(fetch.mock.calls[1][1].body);
  expect(request.confirmed).toBe(true);
  expect(request.loan.aprPercent).toBe(0);
  expect(request.loan.upfrontFeesMinor).toBeNull();
  expect(request.loan.financedAddOns).toEqual([]);
  expect(request.dealerOfferId).toBe("dealer-one");
});

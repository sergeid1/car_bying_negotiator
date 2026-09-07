import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { OutsideFinancing } from "./OutsideFinancing";

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
it("submits a Buyer-selected outside offer against current dealer financing and preserves unknown fees", async () => {
  const fetch = vi.fn().mockResolvedValueOnce({ ok: true, json: async () => ({ id: "finance-one" }) })
    .mockResolvedValueOnce({ ok: true, json: async () => ({ version: 1, createdAt: "2026-09-07", dealerFinancing: { id: "finance-one", inputs: {} }, inputs: {}, result: {
      status: "UNAVAILABLE", issues: ["Outside: all fees must be known."], purchaseDifferences: [],
      dealer: { status: "UNAVAILABLE" }, outside: { status: "UNAVAILABLE" }, outsideMinusDealerCostMinor: null,
      aprOnlyScheduledPaymentsDeltaMinor: null,
    } }) });
  vi.stubGlobal("fetch", fetch);
  render(<OutsideFinancing caseId="case-one" token="secret" />);
  fireEvent.change(screen.getByLabelText("APR (%)"), { target: { value: "0" } });
  fireEvent.click(screen.getByRole("checkbox", { hidden: true }));
  fireEvent.click(screen.getByRole("button", { name: "Save and compare outside financing", hidden: true }));
  await waitFor(() => expect(screen.getByText("Outside: all fees must be known.")).toBeInTheDocument());
  expect(fetch.mock.calls[0][0]).toContain("/finance-offers/latest");
  expect(fetch.mock.calls[1][0]).toContain("/outside-finance-offers");
  const request = JSON.parse(fetch.mock.calls[1][1].body);
  expect(request.dealerFinanceOfferId).toBe("finance-one");
  expect(request.loan.aprPercent).toBe(0);
  expect(request.loan.financedFeesMinor).toBeNull();
  expect(screen.getByText(/No cost delta is available/)).toBeInTheDocument();
});

it("shows full-term cost deltas only for aligned scenarios and labels saved evidence", async () => {
  const scenario = { status: "AVAILABLE", paymentMinor: 57998, finalPaymentMinor: 58026,
    totalScheduledPaymentsMinor: 3479908, totalInterestMinor: 479908, totalTransactionCostMinor: 3479908,
    financedAddOns: [{ label: "Protection", cashPriceMinor: 200000, incrementalScheduledPaymentsMinor: 231999, incrementalInterestMinor: 31999 }] };
  const comparison = { version: 1, createdAt: "2026-09-07", inputs: {}, dealerFinancing: { id: "finance-one", inputs: {} }, result: {
    status: "COMPARABLE", issues: [], purchaseDifferences: [], dealer: scenario, outside: scenario,
    outsideMinusDealerCostMinor: -469908, aprOnlyScheduledPaymentsDeltaMinor: -479908,
  } };
  const fetch = vi.fn().mockResolvedValue({ ok: true, json: async () => comparison });
  vi.stubGlobal("fetch", fetch);
  render(<OutsideFinancing caseId="case-one" token="secret" />);
  fireEvent.click(screen.getByRole("button", { name: "Open saved outside comparison" }));
  await waitFor(() => expect(screen.getByText(/Outside minus dealer Total Transaction Cost: -\$4,699.08/)).toBeInTheDocument());
  expect(screen.getByText(/APR-only change in scheduled payments: -\$4,799.08/)).toBeInTheDocument();
  expect(screen.getAllByText("$34,799.08")).toHaveLength(4);
  expect(screen.getByText(/historical snapshot/)).toBeInTheDocument();
  comparison.result.status = "NOT_COMPARABLE";
  fireEvent.click(screen.getByRole("button", { name: "Open saved outside comparison" }));
  await waitFor(() => expect(screen.getByText(/No cost delta is available/)).toBeInTheDocument());
  expect(screen.queryByText(/Outside minus dealer Total Transaction Cost:/)).not.toBeInTheDocument();
});

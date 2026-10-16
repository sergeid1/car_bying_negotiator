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

it("fills the outside offer from the chosen pre-approval, keeps its loan terms fixed and sends its ID", async () => {
  const fetch = vi.fn()
    .mockResolvedValueOnce({ ok: true, json: async () => ({ chosen: { offerId: "coastal", lender: "Coastal Auto Finance", amountMinor: 3000000,
      aprPercent: 5.49, termMonths: 60, upfrontFeesMinor: 0, expired: false } }) })
    .mockResolvedValueOnce({ ok: true, json: async () => ({ id: "finance-one" }) })
    .mockResolvedValueOnce({ ok: true, json: async () => ({ version: 2, createdAt: "2026-10-16", dealerFinancing: { id: "finance-one", inputs: {} },
      inputs: {}, preApproval: { offerId: "coastal", lender: "Coastal Auto Finance", offerSource: "PARTNER" }, result: {
        status: "UNAVAILABLE", issues: [], purchaseDifferences: [], dealer: { status: "UNAVAILABLE" }, outside: { status: "UNAVAILABLE" },
        outsideMinusDealerCostMinor: null, aprOnlyScheduledPaymentsDeltaMinor: null } }) });
  vi.stubGlobal("fetch", fetch);
  render(<OutsideFinancing caseId="case-one" token="secret" />);
  fireEvent.click(screen.getByRole("button", { name: "Fill in my chosen pre-approval", hidden: true }));
  await waitFor(() => expect(screen.getByText(/Filled in from your chosen pre-approval from Coastal Auto Finance/)).toBeInTheDocument());
  expect(fetch.mock.calls[0][0]).toBe("/api/v1/cases/case-one/loan");
  expect(screen.getByLabelText("APR (%)")).toHaveValue(5.49);
  expect(screen.getByLabelText("APR (%)")).toHaveAttribute("readonly");
  expect(screen.getByLabelText("Amount financed (USD)")).toHaveValue(30000);
  fireEvent.click(screen.getByRole("checkbox", { hidden: true }));
  fireEvent.click(screen.getByRole("button", { name: "Save and compare outside financing", hidden: true }));
  await waitFor(() => expect(screen.getByText("Outside financing: your chosen pre-approval from Coastal Auto Finance.")).toBeInTheDocument());
  const request = JSON.parse(fetch.mock.calls[2][1].body);
  expect(request.preApprovalId).toBe("coastal");
  expect(request.loan.aprPercent).toBe(5.49);
  expect(request.loan.principalMinor).toBe(3000000);
  expect(request.loan.termMonths).toBe(60);
});

it("asks for a chosen pre-approval when there is none", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, json: async () => ({ chosen: null }) }));
  render(<OutsideFinancing caseId="case-one" token="secret" />);
  fireEvent.click(screen.getByRole("button", { name: "Fill in my chosen pre-approval", hidden: true }));
  await waitFor(() => expect(screen.getByText(/Choose a pre-approval that has not expired in Loan pre-approvals first/)).toBeInTheDocument());
  expect(screen.getByLabelText("APR (%)")).not.toHaveAttribute("readonly");
});

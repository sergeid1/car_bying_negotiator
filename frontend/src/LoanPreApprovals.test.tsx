import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, test, vi } from "vitest";
import { LoanPreApprovals, reconciliationText } from "./LoanPreApprovals";
import { loanView } from "./LoanPreApprovals.fixture";

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

function respond(body: unknown, status = 200) { return new Response(JSON.stringify(body), { status }); }

test("a lender's stated payment is shown beside otdog's calculation as a reconciliation", () => {
  const harbor = loanView("OFFERED").results!.sameTerms[3];
  expect(reconciliationText(harbor)).toBe("The lender states $610 a month, $16.11 more than this calculation. Ask the lender what makes up the difference.");
  expect(reconciliationText({ ...harbor, lenderPaymentWithinTolerance: true })).toBe("The lender's stated payment of $610 matches this calculation.");
  expect(reconciliationText({ ...harbor, lenderPaymentMinor: null, lenderMinusCalculatedMinor: null })).toBeNull();
});

test("offers for the asked amount and term are listed in the server's total-cost order, others unranked, with the money line", async () => {
  vi.spyOn(globalThis, "fetch").mockResolvedValue(respond(loanView("OFFERED")));
  render(<LoanPreApprovals caseId="case" token="token" />);
  const ranked = await screen.findByRole("list", { name: "Offers for the amount and term you asked for" });
  expect(ranked.tagName).toBe("OL");
  const items = within(ranked).getAllByRole("listitem");
  expect(items.map(item => item.querySelector("strong")?.textContent)).toEqual([
    "Space Coast Community Credit Union of Brevard County", "Coastal Auto Finance", "Bayfront Credit", "Harbor Lending"]);
  expect(items[0]).toHaveTextContent("Entered by you");
  expect(items[0]).toHaveTextContent("No end date entered");
  expect(items[1]).toHaveTextContent("5.49% APR · 60 months · $30,000");
  expect(items[1]).toHaveTextContent("Monthly payment$573");
  expect(items[1]).toHaveTextContent("Total interest$4,380");
  expect(items[1]).toHaveTextContent("Total cost$34,380");
  expect(items[1]).toHaveTextContent("Conditions: a car up to 10 years old, up to 120,000 miles.");
  expect(items[2]).toHaveTextContent("Upfront fees$395");
  expect(items[3]).toHaveTextContent("Ask the lender what makes up the difference.");
  expect(within(items[1]).getByRole("link", { name: "Continue with Coastal Auto Finance on its own site" }))
    .toHaveAttribute("rel", "noopener noreferrer");
  const different = screen.getByRole("list", { name: "Offers for a different amount or term" });
  expect(different.tagName).toBe("UL");
  expect(different).toHaveTextContent("Differs from your application in its term.");
  expect(screen.getByText(/not ranked against the offers above/)).toBeInTheDocument();
  expect(screen.getByRole("list", { name: "Lenders that did not make an offer" })).toHaveTextContent("Keys Bank: Did not pre-approve");
  expect(screen.getByRole("list", { name: "Expired offers" })).toHaveTextContent("Expired Oct 15, 2026");
  expect(within(screen.getByRole("list", { name: "Expired offers" })).queryByRole("button", { name: /Use this pre-approval/ })).toBeNull();
  expect(screen.getByText(/Companies shown here may pay otdog/)).toHaveTextContent("never changes the order or what otdog says");
  expect(screen.getByRole("link", { name: "How otdog makes money" })).toHaveAttribute("href", "?page=how-otdog-makes-money");
  expect(screen.getByText(/otdog never accepts an offer or signs a loan/)).toBeInTheDocument();
});

test("the written authorization is shown in plain words and nothing is sent until the Buyer gives it", async () => {
  const fetch = vi.spyOn(globalThis, "fetch").mockImplementation(async (_input, init) =>
    init?.method === "POST" ? respond(loanView("OFFERED"), 201) : respond(loanView("APPLIED")));
  render(<LoanPreApprovals caseId="case" token="token" />);
  await userEvent.click(await screen.findByRole("button", { name: "Get pre-approvals" }));
  expect(screen.getByText(/Fair Credit Reporting Act/)).toBeInTheDocument();
  expect(screen.getByText(/^A soft inquiry does not affect your credit score/)).toBeInTheDocument();
  expect(screen.getByText(/otdog never stores them/)).toBeInTheDocument();
  const send = screen.getByRole("button", { name: "Send and get pre-approvals" });
  expect(send).toBeDisabled();
  expect(fetch.mock.calls.filter(([, init]) => init?.method === "POST")).toHaveLength(0);
  await userEvent.click(screen.getByLabelText(/I give this written permission for soft inquiries/));
  await userEvent.click(send);
  await screen.findByRole("list", { name: "Offers for the amount and term you asked for" });
  const [url, init] = fetch.mock.calls.find(([, call]) => call?.method === "POST")!;
  expect(url).toBe("/api/v1/cases/case/loan/pre-approval-requests");
  expect(JSON.parse(String(init!.body))).toEqual({ authorizationTextVersion: "otdog-credit-authorization.draft-1", authorized: true });
  expect((init!.headers as Record<string, string>)["Idempotency-Key"]).toBeTruthy();
});

test("using a pre-approval makes it the Buyer's own financing, and it can be stopped", async () => {
  const fetch = vi.spyOn(globalThis, "fetch").mockImplementation(async (_input, init) =>
    init?.method === "PUT" ? respond(loanView("CHOSEN")) : respond(loanView("OFFERED")));
  render(<LoanPreApprovals caseId="case" token="token" />);
  const ranked = await screen.findByRole("list", { name: "Offers for the amount and term you asked for" });
  await userEvent.click(within(ranked).getByRole("button", { name: "Use this pre-approval from Coastal Auto Finance" }));
  const own = await screen.findByRole("region", { name: "Your own financing" }, { timeout: 3000 });
  expect(own).toHaveTextContent("Coastal Auto Finance: 5.49% APR for 60 months on $30,000.");
  expect(own).toHaveTextContent(/say so plainly when the dealer's financing costs less/);
  const [url, init] = fetch.mock.calls.find(([, call]) => call?.method === "PUT")!;
  expect(url).toBe("/api/v1/cases/case/loan/chosen-offer");
  expect(JSON.parse(String(init!.body))).toEqual({ offerId: "coastal" });
  expect(screen.queryByRole("button", { name: "Use this pre-approval from Coastal Auto Finance" })).toBeNull();
  await userEvent.click(within(own).getByRole("button", { name: "Stop using it as my own financing" }));
  expect(fetch.mock.calls.some(([path, call]) => path === "/api/v1/cases/case/loan/chosen-offer" && call?.method === "DELETE")).toBe(true);
});

test("the application asks for no identity details, fills the car from the case and shows field errors", async () => {
  const fetch = vi.spyOn(globalThis, "fetch").mockImplementation(async (_input, init) => init?.method === "PUT"
    ? respond({ detail: "Check the highlighted loan details.", errors: [
      { field: "amountMinor", message: "Enter the amount you want to borrow, up to $500,000." },
      { field: "housing", message: "Choose whether you own, rent or live another way." }] }, 400)
    : respond(loanView("EMPTY")));
  render(<LoanPreApprovals caseId="case" token="token" />);
  await userEvent.click(await screen.findByRole("button", { name: "Start a loan application" }));
  const form = screen.getByRole("form", { name: "Loan application" });
  expect(within(form).getByLabelText("Make")).toHaveValue("Honda");
  expect(within(form).getByLabelText("Mileage")).toHaveValue("12000");
  expect(within(form).queryByLabelText(/social security|date of birth|name/i)).toBeNull();
  await userEvent.type(within(form).getByLabelText("Amount to borrow ($)"), "30,000");
  await userEvent.click(within(form).getByRole("button", { name: "Save application" }));
  expect(await screen.findByText("Enter the amount you want to borrow, up to $500,000.")).toBeInTheDocument();
  expect(within(form).getByLabelText("Amount to borrow ($)")).toHaveAttribute("aria-invalid", "true");
  const body = JSON.parse(String(fetch.mock.calls.find(([, init]) => init?.method === "PUT")![1]!.body));
  expect(body).toMatchObject({ amountMinor: 3000000, termMonths: 60, vehicle: { make: "Honda", mileage: 12000 }, housing: null });
  expect(Object.keys(body)).not.toContain("socialSecurityNumber");
});

test("an offer from the Buyer's own lender is sent with only what was typed", async () => {
  const fetch = vi.spyOn(globalThis, "fetch").mockImplementation(async (_input, init) =>
    init?.method === "POST" ? respond(loanView("OFFERED"), 201) : respond(loanView("OFFERED")));
  render(<LoanPreApprovals journeyId="journey" onSessionEnded={() => undefined} level={3} />);
  await userEvent.click(await screen.findByRole("button", { name: "Add an offer I already have" }));
  const form = screen.getByRole("form", { name: "An offer you already have" });
  await userEvent.type(within(form).getByLabelText("Lender"), "Space Coast Credit Union");
  await userEvent.type(within(form).getByLabelText("APR (%)"), "4.9");
  await userEvent.click(within(form).getByRole("button", { name: "Add offer" }));
  const [url, init] = fetch.mock.calls.find(([, call]) => call?.method === "POST")!;
  expect(url).toBe("/api/v1/journeys/journey/loan/own-offers");
  expect((init!.headers as Record<string, string>)["Account-Request"]).toBe("1");
  expect(JSON.parse(String(init!.body))).toEqual({ lender: "Space Coast Credit Union", aprPercent: 4.9, amountMinor: null, termMonths: null,
    upfrontFeesMinor: null, lenderPaymentMinor: null, offeredOn: null, expiresOn: null });
});

import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, test, vi } from "vitest";
import { differenceText, InsuranceQuotes, yearlyDifferenceText } from "./InsuranceQuotes";
import { insuranceView } from "./InsuranceQuotes.fixture";

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

function respond(body: unknown, status = 200) { return new Response(JSON.stringify(body), { status }); }

test("a yearly difference is arithmetic against what the Buyer pays now, never a promise", () => {
  const base = { quoteYearlyMinor: 0, currentYearlyMinor: 182000, currentCoverage: "SAME_AS_CHOSEN" as const };
  expect(yearlyDifferenceText({ ...base, quoteMinusCurrentMinor: -4000 })).toBe("$40 a year less than the $1,820 a year you pay now.");
  expect(yearlyDifferenceText({ ...base, quoteMinusCurrentMinor: 216000 })).toBe("$2,160 a year more than the $1,820 a year you pay now.");
  expect(yearlyDifferenceText({ ...base, quoteMinusCurrentMinor: 0 })).toBe("The same yearly cost as the $1,820 a year you pay now.");
  for (const text of [yearlyDifferenceText({ ...base, quoteMinusCurrentMinor: -4000 })]) expect(text).not.toMatch(/sav/i);
  expect(differenceText({ type: "COLLISION", aspect: "DEDUCTIBLE", chosenMinor: 50000, pricedMinor: 100000 }, "Collision"))
    .toBe("Collision deductible: $1,000 here, $500 chosen.");
  expect(differenceText({ type: "COLLISION", aspect: "NOT_IN_QUOTE", chosenMinor: null, pricedMinor: null }, "Collision"))
    .toBe("Collision: you chose it, and this quote leaves it out.");
});

test("quotes for the chosen coverage are listed in the server's order, different coverage unranked, with the money line", async () => {
  vi.spyOn(globalThis, "fetch").mockResolvedValue(respond(insuranceView("QUOTED")));
  render(<InsuranceQuotes caseId="case" token="token" />);
  const ranked = await screen.findByRole("list", { name: "Quotes for the coverage you chose" });
  expect(ranked.tagName).toBe("OL");
  const items = within(ranked).getAllByRole("listitem").filter(item => item.parentElement === ranked);
  expect(items.map(item => item.querySelector("strong")?.textContent)).toEqual([
    "Panhandle Farmers Insurance Exchange of the Gulf Coast", "Synthetic Insurer Alpha", "Synthetic Insurer Beta"]);
  expect(items[0]).toHaveTextContent("Entered by you");
  expect(items[0]).toHaveTextContent("$40 a year less than the $1,820 a year you pay now.");
  expect(items[0]).toHaveTextContent("No end date entered");
  expect(items[2]).toHaveTextContent("$2,069 for 6 months ($4,138 a year)");
  expect(within(items[1]).getByRole("link", { name: "Continue with Synthetic Insurer Alpha on its own site" }))
    .toHaveAttribute("rel", "noopener noreferrer");
  const different = screen.getByRole("list", { name: "Quotes for different coverage" });
  expect(different.tagName).toBe("UL");
  expect(different).toHaveTextContent("Collision deductible: $1,000 here, $500 chosen.");
  expect(screen.getByText(/not ranked against the quotes above/)).toBeInTheDocument();
  expect(screen.getByRole("list", { name: "Insurers that did not quote" })).toHaveTextContent("Synthetic Insurer Delta: Declined to quote");
  expect(screen.getByRole("list", { name: "Expired quotes" })).toHaveTextContent("Expired Oct 15, 2026");
  expect(screen.getByText(/Companies shown here may pay otdog/)).toHaveTextContent("never changes the order or what otdog says");
  expect(screen.getByRole("link", { name: "How otdog makes money" })).toHaveAttribute("href", "?page=how-otdog-makes-money");
  expect(screen.getByText(/You don't have to take any of these quotes/)).toBeInTheDocument();
});

test("the consent is shown in plain words and nothing is sent until the Buyer agrees", async () => {
  const fetch = vi.spyOn(globalThis, "fetch").mockImplementation(async (_input, init) =>
    init?.method === "POST" ? respond(insuranceView("QUOTED"), 201) : respond(insuranceView("APPLIED")));
  render(<InsuranceQuotes caseId="case" token="token" />);
  await userEvent.click(await screen.findByRole("button", { name: "Get quotes" }));
  expect(screen.getByText(/It goes to no one else/)).toBeInTheDocument();
  expect(screen.getByText(/It holds no names, dates of birth/)).toBeInTheDocument();
  const send = screen.getByRole("button", { name: "Send and get quotes" });
  expect(send).toBeDisabled();
  expect(fetch.mock.calls.filter(([, init]) => init?.method === "POST")).toHaveLength(0);
  await userEvent.click(screen.getByLabelText(/I agree to send my application to the synthetic test partner/));
  await userEvent.click(send);
  await screen.findByRole("list", { name: "Quotes for the coverage you chose" });
  const posted = fetch.mock.calls.find(([, init]) => init?.method === "POST")!;
  expect(String(posted[0])).toBe("/api/v1/cases/case/insurance/quote-requests");
  expect(JSON.parse(String(posted[1]!.body))).toEqual({ consentTextVersion: "otdog-insurance-consent.draft-1", agreed: true });
  expect((posted[1]!.headers as Record<string, string>)["Idempotency-Key"]).toBeTruthy();
  expect((posted[1]!.headers as Record<string, string>)["Case-Access-Token"]).toBe("token");
});

test("a new application starts from the case's car and its Florida minimums, and field errors are shown by the field", async () => {
  const fetch = vi.spyOn(globalThis, "fetch").mockImplementation(async (_input, init) => init?.method === "PUT"
    ? respond({ detail: "Check the highlighted insurance details.", errors: [
      { field: "drivers[0].ageYears", message: "Enter the driver's age, from 15 to 120." },
      { field: "garaging.zip", message: "Enter the five-digit ZIP code where the car is kept overnight." },
      { field: "vehicle.usage", message: "Choose how the car is mostly driven." }] }, 400)
    : respond(insuranceView("EMPTY")));
  render(<InsuranceQuotes caseId="case" token="token" />);
  await userEvent.click(await screen.findByRole("button", { name: "Start an insurance application" }));
  expect(screen.getByLabelText("Make")).toHaveValue("Honda");
  expect(screen.getByLabelText("VIN (optional)")).toHaveValue("1HGCM82633A004352");
  expect(screen.getByText(/Filled in from this case's car/)).toBeInTheDocument();
  expect(screen.getByRole("checkbox", { name: "Property damage liability" })).toBeChecked();
  expect(screen.getByRole("checkbox", { name: "Personal injury protection (PIP)" })).toBeChecked();
  expect(screen.getByRole("checkbox", { name: "Collision" })).not.toBeChecked();
  await userEvent.click(screen.getByRole("checkbox", { name: "Collision" }));
  expect(screen.getByLabelText("Collision: deductible")).toHaveValue("50000");
  await userEvent.click(screen.getByRole("button", { name: "Add another driver" }));
  expect(screen.getByRole("group", { name: "Driver 2" })).toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "Remove driver 2" }));
  await userEvent.type(screen.getByLabelText("Age"), "forty");
  await userEvent.click(screen.getByRole("button", { name: "Save application" }));
  expect(await screen.findByText("Enter the driver's age, from 15 to 120.")).toBeInTheDocument();
  expect(screen.getByLabelText("Age")).toHaveAttribute("aria-invalid", "true");
  expect(screen.getByLabelText("ZIP code")).toHaveAccessibleDescription("Enter the five-digit ZIP code where the car is kept overnight.");
  const body = JSON.parse(String(fetch.mock.calls.find(([, init]) => init?.method === "PUT")![1]!.body));
  expect(body.drivers).toEqual([{ ageYears: -1, licenceState: "FL", yearsLicensed: null, atFaultAccidents: 0, violations: 0 }]);
  expect(body.coverage.map((line: { type: string }) => line.type)).toEqual(["PROPERTY_DAMAGE_LIABILITY", "PERSONAL_INJURY_PROTECTION", "COLLISION"]);
  expect(body.currentPolicy).toBeNull();
});

// Ticket 138: each pilot state's minimums, from the server's cited data.
async function startApplication(overrides: Parameters<typeof insuranceView>[1]) {
  vi.spyOn(globalThis, "fetch").mockImplementation(async () => respond(insuranceView("EMPTY", overrides)));
  render(<InsuranceQuotes caseId="case" token="token" />);
  await userEvent.click(await screen.findByRole("button", { name: "Start an insurance application" }));
}

test("a Texas application starts at 30/60/25 and leaves PIP and uninsured motorist to the Buyer", async () => {
  await startApplication({ buyerState: "TX" });
  expect(screen.getByLabelText("State")).toHaveValue("TX");
  expect(screen.getByLabelText("State that issued the licence")).toHaveValue("TX");
  expect(screen.getByLabelText("Bodily injury liability: limit per person")).toHaveValue("3000000");
  expect(screen.getByLabelText("Bodily injury liability: limit per accident")).toHaveValue("6000000");
  expect(screen.getByLabelText("Property damage liability: limit per accident")).toHaveValue("2500000");
  for (const name of ["Personal injury protection (PIP)", "Uninsured motorist"]) {
    expect(screen.getByRole("checkbox", { name })).not.toBeChecked();
    expect(screen.getByRole("checkbox", { name })).toHaveAccessibleDescription(
      expect.stringContaining("Texas policies include it unless you reject it in writing. Whether to keep it is your choice."));
  }
  expect(screen.getByRole("checkbox", { name: "Bodily injury liability" }))
    .toHaveAccessibleDescription(expect.stringContaining("Texas requires at least $30,000 per person and $60,000 per accident."));
  expect(screen.queryByText(/below Texas's minimum/)).not.toBeInTheDocument();
});

test("a North Carolina application starts at 50/100/50 with uninsured motorist at the liability minimum", async () => {
  await startApplication({ buyerState: "NC" });
  expect(screen.getByLabelText("State")).toHaveValue("NC");
  expect(screen.getByLabelText("Bodily injury liability: limit per person")).toHaveValue("5000000");
  expect(screen.getByLabelText("Bodily injury liability: limit per accident")).toHaveValue("10000000");
  expect(screen.getByLabelText("Property damage liability: limit per accident")).toHaveValue("5000000");
  expect(screen.getByLabelText("Uninsured motorist: limit per person")).toHaveValue("5000000");
  expect(screen.getByLabelText("Uninsured motorist: limit per accident")).toHaveValue("10000000");
  expect(screen.getByRole("checkbox", { name: "Personal injury protection (PIP)" })).not.toBeChecked();
  expect(screen.getByText(/also includes underinsured motorist coverage/)).toBeInTheDocument();
});

test("without a known state nothing defaults to Florida, and a state outside the pilot gets no invented minimum", async () => {
  await startApplication({ buyerState: null });
  expect(screen.getByLabelText("State")).toHaveValue("");
  expect(screen.getByLabelText("State that issued the licence")).toHaveValue("");
  expect(screen.getAllByRole("checkbox").filter(box => (box as HTMLInputElement).checked)).toHaveLength(0);
  await userEvent.selectOptions(screen.getByLabelText("State"), "GA");
  expect(screen.getByText("Minimums for GA are not listed here yet.")).toBeInTheDocument();
  expect(screen.queryByText(/requires at least/)).not.toBeInTheDocument();
  expect(screen.getAllByRole("checkbox").filter(box => (box as HTMLInputElement).checked)).toHaveLength(0);
  // Before the Buyer edits coverage, choosing a pilot state starts from its minimums.
  await userEvent.selectOptions(screen.getByLabelText("State"), "TX");
  expect(screen.getByLabelText("Bodily injury liability: limit per person")).toHaveValue("3000000");
});

test("changing the state after editing coverage keeps the Buyer's choices and offers a reset", async () => {
  await startApplication({ buyerState: "FL" });
  await userEvent.click(screen.getByRole("checkbox", { name: "Collision" }));
  await userEvent.selectOptions(screen.getByLabelText("State"), "NC");
  expect(screen.getByRole("checkbox", { name: "Collision" })).toBeChecked();
  expect(screen.getByRole("checkbox", { name: "Personal injury protection (PIP)" })).toBeChecked();
  expect(screen.getByText("The state changed, and your coverage choices are kept.")).toBeInTheDocument();
  // The kept coverage falls short of North Carolina's, and the warning says so before anything is sent.
  const warning = screen.getByRole("status");
  expect(warning).toHaveTextContent("This coverage is below North Carolina's minimum:");
  expect(warning).toHaveTextContent("Bodily injury liability: North Carolina requires at least $50,000 per person, $100,000 per accident, and it is not chosen.");
  expect(warning).toHaveTextContent("Property damage liability: North Carolina requires at least $50,000 per accident, and less is chosen.");
  await userEvent.click(screen.getByRole("button", { name: "Reset coverage to North Carolina's minimums" }));
  expect(screen.getByRole("checkbox", { name: "Collision" })).not.toBeChecked();
  expect(screen.getByRole("checkbox", { name: "Personal injury protection (PIP)" })).not.toBeChecked();
  expect(screen.getByLabelText("Property damage liability: limit per accident")).toHaveValue("5000000");
  expect(screen.getByRole("status")).toBeEmptyDOMElement();
  expect(screen.queryByRole("button", { name: /Reset coverage/ })).not.toBeInTheDocument();
});

test.each([
  ["FL", "Personal injury protection (PIP)", "Florida requires at least $10,000 per person, and it is not chosen."],
  ["TX", "Bodily injury liability: limit per person", "Texas requires at least $30,000 per person, $60,000 per accident, and less is chosen."],
  ["NC", "Uninsured motorist", "North Carolina requires at least $50,000 per person, $100,000 per accident, and it is not chosen."],
])("coverage below the %s minimum is warned about in the form", async (state, control, message) => {
  await startApplication({ buyerState: state });
  if (control.includes(":")) await userEvent.selectOptions(screen.getByLabelText(control), "2500000");
  else await userEvent.click(screen.getByRole("checkbox", { name: control }));
  expect(screen.getByRole("status")).toHaveTextContent(message);
});

test("the consent step warns when the saved coverage is below the garaging state's minimum", async () => {
  const saved = insuranceView("APPLIED").application!;
  const application = { ...saved, garaging: { zip: "75201", state: "TX" }, coverage: saved.coverage.map(line =>
    line.type === "PROPERTY_DAMAGE_LIABILITY" ? { ...line, perAccidentLimitMinor: 2000000 } : line) };
  vi.spyOn(globalThis, "fetch").mockImplementation(async () => respond(insuranceView("APPLIED", { application })));
  render(<InsuranceQuotes caseId="case" token="token" />);
  await userEvent.click(await screen.findByRole("button", { name: "Get quotes" }));
  expect(screen.getByText("This coverage is below Texas's minimum:")).toBeInTheDocument();
  expect(screen.getByText("Property damage liability: Texas requires at least $25,000 per accident, and less is chosen.")).toBeInTheDocument();
  expect(screen.queryByText(/Bodily injury liability: Texas/)).not.toBeInTheDocument();
});

test("a Buyer adds a quote they already have, for different coverage, and removes one they entered", async () => {
  const fetch = vi.spyOn(globalThis, "fetch").mockImplementation(async (_input, init) =>
    respond(insuranceView("QUOTED"), init?.method === "POST" ? 201 : 200));
  render(<InsuranceQuotes caseId="case" token="token" />);
  await userEvent.click(await screen.findByRole("button", { name: "Add a quote I already have" }));
  const form = screen.getByRole("form", { name: "A quote you already have" });
  await userEvent.type(within(form).getByLabelText("Insurer"), "Suncoast Direct");
  await userEvent.type(within(form).getByLabelText("Premium for the whole term ($)"), "990");
  await userEvent.click(within(form).getByRole("radio", { name: "6 months" }));
  await userEvent.click(within(form).getByRole("radio", { name: "No, different coverage" }));
  await userEvent.click(within(form).getByRole("checkbox", { name: "Bodily injury liability" }));
  await userEvent.click(within(form).getByRole("button", { name: "Add quote" }));
  await waitFor(() => expect(screen.queryByRole("form", { name: "A quote you already have" })).not.toBeInTheDocument());
  const posted = JSON.parse(String(fetch.mock.calls.find(([, init]) => init?.method === "POST")![1]!.body));
  expect(posted).toMatchObject({ insurer: "Suncoast Direct", premiumMinor: 99000, term: "SIX_MONTHS", sameCoverage: false });
  expect(posted.coverage.map((line: { type: string }) => line.type)).toEqual(expect.arrayContaining(["COLLISION"]));
  expect(posted.coverage.map((line: { type: string }) => line.type)).not.toContain("BODILY_INJURY_LIABILITY");
  await userEvent.click(screen.getByRole("button", { name: "Remove Old Renewal Notice's quote" }));
  await waitFor(() => expect(fetch.mock.calls.some(([input, init]) => init?.method === "DELETE"
    && String(input) === "/api/v1/cases/case/insurance/own-quotes/old")).toBe(true));
});

test("a journey's application uses the account session, marks writes and hands an ended sign-in back", async () => {
  const ended = vi.fn();
  const fetch = vi.spyOn(globalThis, "fetch").mockImplementation(async (_input, init) =>
    init?.method === "DELETE" ? new Response(null, { status: 401 })
      : respond(insuranceView("APPLIED", { holder: "JOURNEY", caseVehicle: null })));
  render(<InsuranceQuotes journeyId="journey-1" onSessionEnded={ended} level={3} />);
  expect(await screen.findByRole("heading", { level: 3, name: "Car insurance quotes" })).toBeInTheDocument();
  expect(screen.getByText(/every case in the journey uses the same one/)).toBeInTheDocument();
  expect(String(fetch.mock.calls[0][0])).toBe("/api/v1/journeys/journey-1/insurance");
  await userEvent.click(screen.getByRole("button", { name: "Remove application" }));
  await userEvent.click(screen.getByRole("button", { name: "Remove the application and its quotes" }));
  await waitFor(() => expect(ended).toHaveBeenCalled());
  const removal = fetch.mock.calls.find(([, init]) => init?.method === "DELETE")!;
  expect((removal[1]!.headers as Record<string, string>)["Account-Request"]).toBe("1");
  expect((removal[1]!.headers as Record<string, string>)["Case-Access-Token"]).toBeUndefined();
});

test("quotes for an earlier version of the application are called out", async () => {
  const view = insuranceView("QUOTED");
  vi.spyOn(globalThis, "fetch").mockResolvedValue(respond({ ...view, results: { ...view.results!,
    request: { ...view.results!.request!, outdated: true } } }));
  render(<InsuranceQuotes caseId="case" token="token" />);
  expect(await screen.findByText(/Your application changed after these quotes were requested/)).toHaveAttribute("role", "alert");
  expect(screen.getByRole("button", { name: "Get new quotes" })).toBeInTheDocument();
});

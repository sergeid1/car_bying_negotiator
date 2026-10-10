import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, test, vi } from "vitest";
import { AtTheDealer, DealerCameraEntry, type DeskRouting, type FirstResult } from "./AtTheDealer";
import App from "./App";

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

const AGREED_VIN = "1HGCM82633A004352", OTHER_VIN = "1M8GDM9AXKP042788";
const photo = () => new File(["desk"], "desk.jpg", { type: "image/jpeg" });
const questions = (trade: "NONE" | "WHETHER" | "DETAILS" = "WHETHER", loan = false): DeskRouting["questions"] => ({
  trade: { ask: trade, paperworkShowsTrade: trade === "DETAILS", recorded: trade === "NONE" ? {
    id: "t", revision: 1, vehicle: { vin: "5J6RW2H52ML000002", decodeOutcome: "DECODED", source: "fake", decoderVersion: "v1",
      decodedAt: "2026-10-09T00:00:00Z", year: "2021", make: "Honda", model: "CR-V", trim: "EX" },
    mileage: 61000, condition: "GOOD", moneyOwed: "YES", lender: "Credit union", payoffMinor: 1240000, payoffGoodThrough: "2026-10-20",
    payoffUnknown: false, payoffStale: false, complete: true, changedAt: "2026-10-09T00:00:00Z", offers: [], bestOfferId: null } : null },
  financing: { ask: loan, paperworkShowsLoan: loan, ownAprPercent: null, ownTermMonths: null },
});
const routing = (route: DeskRouting["route"], extra: Partial<DeskRouting> = {}): DeskRouting => ({
  status: "READ", documentId: "d1", documentType: "FOUR_SQUARE_WORKSHEET", vin: route === "FRESH_CASE" ? OTHER_VIN : AGREED_VIN, route,
  agreedVin: route === "AGREED_CAR" || route === "FRESH_CASE" ? AGREED_VIN : null, caseVin: null,
  freshCase: route === "FRESH_CASE" ? { vin: OTHER_VIN, dealerPostalCode: "27604", purchaseMethod: "FINANCE" } : null,
  questions: questions(), ...extra });
const addOn: FirstResult = { route: "THIS_CASE", source: "READ_FROM_PAPERWORK",
  outTheDoor: { amountMinor: 2829500, agreedMinor: null, differenceMinor: null },
  lead: { code: "ADD_ON", summary: "Service contract, $1,495, is an add-on.", amountMinor: 149500,
    deskQuestion: "Is the Service contract optional? Please show me the price without it." },
  findings: [] };
addOn.findings = [addOn.lead!, { code: "DEALER_FEE", summary: "Doc fee, $899, is a dealer charge.", amountMinor: 89900,
  deskQuestion: "What is the Doc fee for, and will you lower or remove it?" }];

type Call = { url: string; method: string; body?: string; headers: Record<string, string> };
/** Routes the flow's requests; `routings` are served in order per case, the last one repeating. */
function stub({ routings = {}, result = addOn }: { routings?: Record<string, DeskRouting[]>; result?: FirstResult } = {}) {
  const calls: Call[] = [];
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    const url = String(input), method = init?.method ?? "GET";
    calls.push({ url, method, body: typeof init?.body === "string" ? init.body : undefined,
      headers: (init?.headers ?? {}) as Record<string, string> });
    const caseId = url.match(/\/cases\/([^/?]+)/)?.[1] ?? "";
    if (url.endsWith("/document-limits")) return new Response(JSON.stringify({ acceptedMediaTypes: ["application/pdf", "image/jpeg"],
      maxFileBytes: 20971520, maxPages: 25, maxPixels: 40000000, minWidth: 2, minHeight: 2 }));
    if (url.endsWith("/intake-options")) return new Response(JSON.stringify({ supported: ["US-NC"], consentPolicies: { serviceProcessing: "service.v1" } }));
    if (url === "/api/v1/cases" && method === "POST") {
      const id = "new";
      return new Response(JSON.stringify({ case: { id }, accessToken: `${id}-token` }), { status: 201 });
    }
    if (url.endsWith("/documents") && method === "POST") return new Response(JSON.stringify({
      document: { id: `${caseId}-doc`, pages: [] }, operation: { id: `${caseId}-op`, stage: "QUEUED" } }), { status: 202 });
    if (url.includes("/operations/")) {
      const id = url.split("/operations/")[1].replace("-op", "");
      return new Response(JSON.stringify({ id: `${id}-op`, documentId: `${id}-doc`, stage: "EXTRACTION_COMPLETE", retryCount: 0, updatedAt: "now" }));
    }
    if (url.includes("/documents/")) return new Response(JSON.stringify({ pages: [] }));
    if (url.includes("/desk-start/result")) return new Response(JSON.stringify(result));
    if (url.includes("/desk-start")) {
      const queue = routings[caseId] ?? [routing("THIS_CASE")];
      return new Response(JSON.stringify(queue.length > 1 ? queue.shift() : queue[0]));
    }
    if (url.endsWith("/vehicle-evidence")) return new Response(JSON.stringify({ identityConflicts: [], reliableForAnalysis: true }));
    if (url.endsWith("/trade/carry-over")) return new Response(JSON.stringify({ holder: "JOURNEY", trade: null, analysis: {}, conditions: [] }));
    if (url.endsWith("/trade")) return new Response(JSON.stringify({ holder: "CASE", trade: null, analysis: { analyzedAt: null, outdated: false },
      conditions: [{ value: "GOOD", label: "Good", description: "" }, { value: "FAIR", label: "Fair", description: "" }] }));
    // The rest of the page's own requests are not part of this flow.
    return new Response(null, { status: 404 });
  });
  return calls;
}
const compared = (calls: Call[]) => calls.filter(call => call.url.includes("/desk-start/result"));

test("the header control reaches the phone's camera in one tap", async () => {
  const onPhotos = vi.fn();
  render(<header><DealerCameraEntry onPhotos={onPhotos} /></header>);
  const camera = screen.getByLabelText(/I'm at the dealer/);
  expect(camera).toHaveAttribute("type", "file");
  expect(camera).toHaveAttribute("capture", "environment");
  expect(camera.getAttribute("accept")).toContain("image/jpeg");
  await userEvent.upload(camera, photo());
  expect(onPhotos).toHaveBeenCalledWith([expect.any(File)]);
});

test("a Buyer with no case answers two ZIP codes, confirms the car, and gets the largest finding with its desk question", async () => {
  const calls = stub({ routings: { new: [routing("THIS_CASE")] } });
  const opened = vi.fn();
  render(<AtTheDealer files={[photo()]} pollDelayMs={1} onCaseOpened={opened} onFreshCase={vi.fn()} onDeskCheck={vi.fn()} onClose={vi.fn()} />);
  await userEvent.type(screen.getByLabelText("Your ZIP code"), "27601");
  await userEvent.type(screen.getByLabelText("Dealer ZIP code"), "27604");
  await userEvent.click(screen.getByRole("button", { name: "Read my paperwork" }));
  expect(opened).toHaveBeenCalledWith({ caseId: "new", token: "new-token" });
  expect(await screen.findByText(AGREED_VIN)).toBeInTheDocument();
  expect(compared(calls)).toHaveLength(0);
  expect(calls.some(call => call.method === "POST" && call.url === "/api/v1/cases/new/documents")).toBe(true);
  await userEvent.click(screen.getByRole("button", { name: "Yes, check this car" }));
  const vehicle = calls.find(call => call.url.endsWith("/vehicle-evidence"))!;
  expect(JSON.parse(vehicle.body!)).toMatchObject({ vin: AGREED_VIN, confirmed: true });
  // The paperwork cannot say whether there is a trade, so that alone is asked.
  await userEvent.click(within(screen.getByRole("group", { name: "Are you trading a car in?" })).getByLabelText("No"));
  expect(screen.queryByText(/own loan offer/)).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "Show my result" }));
  const heading = await screen.findByRole("heading", { name: "Out the door: $28,295" });
  expect(heading).toHaveFocus();
  expect(screen.getByText("Service contract, $1,495, is an add-on.")).toBeInTheDocument();
  expect(screen.getByText("Is the Service contract optional? Please show me the price without it.")).toBeInTheDocument();
  expect(screen.getByText("1 more to ask about")).toBeInTheDocument();
  expect(JSON.parse(compared(calls)[0].body!)).toEqual({ route: "THIS_CASE", documentId: "new-doc" });
  expect(calls.some(call => call.method === "PUT")).toBe(false);
});

test("the agreed car's paperwork is compared only after the Buyer confirms, and the recorded trade is reused", async () => {
  const calls = stub({ routings: { case: [routing("AGREED_CAR", { questions: questions("NONE") })] }, result: { ...addOn, route: "AGREED_CAR",
    outTheDoor: { amountMinor: 2829500, agreedMinor: 2680000, differenceMinor: 149500 } } });
  const deskCheck = vi.fn();
  render(<AtTheDealer files={[photo()]} open={{ caseId: "case", token: "token" }} pollDelayMs={1}
    onCaseOpened={vi.fn()} onFreshCase={vi.fn()} onDeskCheck={deskCheck} onClose={vi.fn()} />);
  await userEvent.click(await screen.findByRole("button", { name: "Check it against my Agreed Price" }));
  // The trade is already recorded and nothing shows a loan, so no question is asked.
  expect(await screen.findByText("$1,495 more than your Agreed Price of $26,800.")).toBeInTheDocument();
  expect(JSON.parse(compared(calls)[0].body!).route).toBe("AGREED_CAR");
  expect(calls.some(call => call.url.endsWith("/vehicle-evidence"))).toBe(false);
  await userEvent.click(screen.getByRole("button", { name: "Open the full Desk Check" }));
  expect(deskCheck).toHaveBeenCalled();
});

test("another car's VIN starts a separate case with one question, carries the trade and re-sends the photos", async () => {
  const calls = stub({ routings: { case: [routing("FRESH_CASE")], new: [routing("THIS_CASE", { vin: OTHER_VIN, caseVin: OTHER_VIN })] } });
  const fresh = vi.fn();
  render(<AtTheDealer files={[photo()]} open={{ caseId: "case", token: "token" }} pollDelayMs={1}
    onCaseOpened={vi.fn()} onFreshCase={fresh} onDeskCheck={vi.fn()} onClose={vi.fn()} />);
  expect(await screen.findByRole("alert")).toHaveTextContent(`It shows VIN ${OTHER_VIN}, not your agreed car (VIN ${AGREED_VIN})`);
  await userEvent.click(screen.getByRole("button", { name: "Start a separate case for this car" }));
  expect(screen.getByLabelText("Dealer ZIP code")).toHaveValue("27604");
  expect(screen.getByLabelText("How you are paying")).toHaveValue("FINANCE");
  await userEvent.type(screen.getByLabelText("Your ZIP code"), "27601");
  await userEvent.click(screen.getByRole("button", { name: "Start the separate case" }));
  await waitFor(() => expect(fresh).toHaveBeenCalledWith(expect.objectContaining({ caseId: "new", vin: OTHER_VIN,
    agreed: { caseId: "case", token: "token" }, agreedVin: AGREED_VIN, files: [] })));
  expect(JSON.parse(calls.find(call => call.url === "/api/v1/cases/new/vehicle-evidence")!.body!).vin).toBe(OTHER_VIN);
  const carry = calls.find(call => call.url.endsWith("/trade/carry-over"))!;
  expect(carry.headers["Source-Case-Access-Token"]).toBe("token");
  expect(JSON.parse(carry.body!)).toEqual({ sourceCaseId: "case" });
  // The fresh case reads its own copy of the photos and is not confirmed a second time.
  await waitFor(() => expect(calls.some(call => call.method === "POST" && call.url === "/api/v1/cases/new/documents")).toBe(true));
  await userEvent.click(await screen.findByLabelText("No"));
  await userEvent.click(screen.getByRole("button", { name: "Show my result" }));
  await screen.findByRole("heading", { name: "Out the door: $28,295" });
  expect(compared(calls).map(call => call.url)).toEqual(["/api/v1/cases/new/desk-start/result"]);
});

test("a photo that cannot be read leads to the typed four-square, and the trade the worksheet shows is recorded once", async () => {
  const typedResult: FirstResult = { route: "THIS_CASE", source: "TYPED_BY_BUYER", outTheDoor: { amountMinor: null, agreedMinor: null, differenceMinor: null },
    lead: { code: "PAYMENT_WITHOUT_TOTAL", summary: "This shows a monthly payment but no out-the-door total.", amountMinor: null,
      deskQuestion: "What is the out-the-door total, and what price, APR and term is this payment based on?" }, findings: [] };
  typedResult.findings = [typedResult.lead!];
  const calls = stub({ routings: { case: [routing("THIS_CASE", { status: "UNREADABLE", vin: null, documentType: null })] }, result: typedResult });
  render(<AtTheDealer files={[photo()]} open={{ caseId: "case", token: "token" }} pollDelayMs={1}
    onCaseOpened={vi.fn()} onFreshCase={vi.fn()} onDeskCheck={vi.fn()} onClose={vi.fn()} />);
  expect(await screen.findByRole("heading", { name: "This photo could not be read. Type the numbers instead" })).toBeInTheDocument();
  await userEvent.type(screen.getByLabelText("Selling price"), "25,000");
  await userEvent.type(screen.getByLabelText("Trade-in allowance"), "8000");
  await userEvent.type(screen.getByLabelText("Monthly payment"), "529");
  await userEvent.type(screen.getByLabelText("Term (months)"), "72");
  await userEvent.click(screen.getByRole("button", { name: "Check these numbers" }));
  // The worksheet shows a trade and a loan, so both are asked; nothing else is.
  expect(await screen.findByText(/This paperwork shows a trade-in/)).toBeInTheDocument();
  await userEvent.type(screen.getByLabelText("Trade-in VIN"), "5J6RW2H52ML000002");
  await userEvent.type(screen.getByLabelText("Mileage"), "61,000");
  await userEvent.selectOptions(screen.getByLabelText("Condition"), "GOOD");
  await userEvent.click(within(screen.getByRole("group", { name: "Do you still owe money on it?" })).getByLabelText("I'm not sure"));
  const loan = screen.getByRole("group", { name: /Do you have your own loan offer/ });
  await userEvent.click(within(loan).getByLabelText("Yes"));
  await userEvent.type(screen.getByLabelText("Its APR (%)"), "5.9");
  await userEvent.click(screen.getByRole("button", { name: "Show my result" }));
  expect(await screen.findByRole("heading", { name: "No out-the-door total shown" })).toBeInTheDocument();
  expect(screen.getByText(/as you typed it/)).toBeInTheDocument();
  expect(JSON.parse(calls.find(call => call.method === "PUT")!.body!)).toMatchObject({
    vin: "5J6RW2H52ML000002", mileage: 61000, condition: "GOOD", moneyOwed: "NOT_SURE" });
  expect(JSON.parse(compared(calls)[0].body!)).toEqual({ route: "THIS_CASE", ownAprPercent: 5.9, typed: {
    sellingPriceMinor: 2500000, tradeAllowanceMinor: 800000, monthlyPaymentMinor: 52900, termMonths: 72 } });
});

test("typed amounts that are not numbers are refused in place without contacting the service", async () => {
  const calls = stub({ routings: { case: [routing("THIS_CASE", { status: "UNREADABLE", vin: null })] } });
  render(<AtTheDealer files={[photo()]} open={{ caseId: "case", token: "token" }} pollDelayMs={1}
    onCaseOpened={vi.fn()} onFreshCase={vi.fn()} onDeskCheck={vi.fn()} onClose={vi.fn()} />);
  await userEvent.type(await screen.findByLabelText("Selling price"), "about 25k");
  await userEvent.click(screen.getByRole("button", { name: "Check these numbers" }));
  expect(screen.getByRole("alert")).toHaveTextContent("Enter a dollar amount");
  expect(screen.getByLabelText("Selling price")).toHaveAttribute("aria-invalid", "true");
  expect(compared(calls)).toHaveLength(0);
});

test("the header entry opens the flow over the start screen", async () => {
  stub();
  render(<App pollDelayMs={0} />);
  await userEvent.upload(screen.getByLabelText(/I'm at the dealer/), photo());
  expect(await screen.findByRole("heading", { name: "Check the paperwork in front of you" })).toBeInTheDocument();
  expect(screen.queryByRole("heading", { name: "Start your Buying Case" })).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "Close" }));
  expect(await screen.findByRole("heading", { name: "Start your Buying Case" })).toBeInTheDocument();
});

test("saying yes to a trade-in links to the free value checks, with the VIN and mileage typed so far to copy", async () => {
  stub({ routings: { new: [routing("THIS_CASE")] } });
  render(<AtTheDealer files={[photo()]} pollDelayMs={1} onCaseOpened={vi.fn()} onFreshCase={vi.fn()} onDeskCheck={vi.fn()} onClose={vi.fn()} />);
  await userEvent.type(screen.getByLabelText("Your ZIP code"), "27601");
  await userEvent.type(screen.getByLabelText("Dealer ZIP code"), "27604");
  await userEvent.click(screen.getByRole("button", { name: "Read my paperwork" }));
  await userEvent.click(await screen.findByRole("button", { name: "Yes, check this car" }));
  expect(screen.queryByRole("heading", { name: "Check what your car is worth" })).not.toBeInTheDocument();
  await userEvent.click(within(screen.getByRole("group", { name: "Are you trading a car in?" })).getByLabelText("Yes"));
  const section = screen.getByRole("heading", { name: "Check what your car is worth" }).closest("section")!;
  expect(within(section).getByRole("link", { name: /Carfax value lookup/ })).toHaveAttribute("href", "https://www.carfax.com/value/");
  expect(within(section).getByRole("link", { name: /CarMax instant offer/ })).toHaveAttribute("rel", "noopener noreferrer");
  expect(within(section).queryByRole("button", { name: "Copy VIN" })).not.toBeInTheDocument();
  await userEvent.type(screen.getByLabelText("Trade-in VIN"), "5J6RW2H52ML000002");
  await userEvent.type(screen.getByLabelText("Mileage"), "61,000");
  expect(within(section).getByText("5J6RW2H52ML000002")).toBeInTheDocument();
  expect(within(section).getByRole("button", { name: "Copy mileage" })).toBeInTheDocument();
  expect(within(section).getByText("61000")).toBeInTheDocument();
});
